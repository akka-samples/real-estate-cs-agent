package realestate.application;


import akka.javasdk.annotations.ComponentId;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.timer.TimerScheduler;
import akka.javasdk.workflow.Workflow;
import com.typesafe.config.Config;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import realestate.domain.ProspectState;

@ComponentId("prospect-processing-workflow")
public class ProspectProcessingWorkflow extends Workflow<ProspectState> {

  private final Logger logger = LoggerFactory.getLogger(getClass());

  private final TimerScheduler timerScheduler;
  private final ComponentClient componentClient;
  private final Duration followUpTimer;


  public ProspectProcessingWorkflow(
      TimerScheduler timerScheduler,
      ComponentClient componentClient,
      Config config) {
    this.timerScheduler = timerScheduler;
    this.componentClient = componentClient;
    this.followUpTimer = config.getDuration("realestate.follow-up.timer");
  }



  @Override
  public WorkflowSettings settings() {
    return WorkflowSettings.builder()
      .defaultStepTimeout(Duration.ofMinutes(1 ))
      .defaultStepRecovery(maxRetries(2).failoverTo(ProspectProcessingWorkflow::errorStep))
      .build();
  }

  private StepEffect collectingClientDetails() {

    String msg;

    if (currentState().status() != ProspectState.Status.CLOSED
        && currentState().status() != ProspectState.Status.ERROR) {

      currentState().unreadMessages().forEach(m -> logger.debug("Processing pending email: {}", m));
      msg =
          componentClient
              .forAgent()
              .inSession(commandContext().workflowId())
              .method(CustomerServiceAgent::processEmails)
              .invoke(new CustomerServiceAgent.ProcessEmailsCmd(currentState().unreadMessages()));
    } else {
      msg = "unexpected status " + currentState().status();
    }

    logger.debug("Current status: [{}], processing from AI: [{}]", currentState().status(), msg);

    return switch(msg) {
      case "WAIT_REPLY" ->
        stepEffects()
          .updateState(currentState().waitingReply())
          .thenTransitionTo(ProspectProcessingWorkflow::waitingReply);

      case "ALL_INFO_COLLECTED" -> {
        logger.info("All info collected for client: [{}]", currentState().email());
        yield stepEffects()
          .updateState(currentState().closed())
          .thenEnd();
      }
      default -> {
        logger.error("Could not process message from AI: [{}]", msg);
        yield stepEffects().thenPause();
      }
    };
  }

  private StepEffect waitingReply() {
    var call =
      componentClient
        .forWorkflow(commandContext().workflowId())
        .method(ProspectProcessingWorkflow::followUp).deferred();

    var timerId = "follow-up-" + commandContext().workflowId();

    timerScheduler.createSingleTimer(
      timerId,
      followUpTimer,
      call);
    logger.debug("Created timer for follow up in {}. timerId={} ", followUpTimer, timerId);

    return stepEffects().thenPause();
  }

  private StepEffect errorStep() {
    logger.error("Workflow for for customer [{}] failed", currentState().email());
    return stepEffects().updateState(currentState().error()).thenEnd();
  }

  // Commands that can ben received by workflow
  public record ProcessMessage(String sender, String subject, String content) { }

  public Effect<String> processNewEmail(ProcessMessage msg) {

    var newMsg = ProspectState.Message.UserMessage(
        msg.sender(),
        msg.subject(),
        msg.content());

    var updatedState = currentState() == null
        ? ProspectState.EMPTY.withEmail(msg.sender()).addUnreadMessage(newMsg)
        : currentState().addUnreadMessage(newMsg);

    // delete the existing timer if it exists since we have a reply now
    if (currentState() != null)
      timerScheduler.delete("follow-up-" + currentState().email());

    return effects()
        .updateState(updatedState)
        .transitionTo(ProspectProcessingWorkflow::collectingClientDetails)
        .thenReply("Processing started");
  }

  public Effect<String> followUp() {
    if (currentState() == null || currentState().isWaitingReply()) {
      return effects().pause().thenReply("No pending email to follow up");
    }

    logger.info("Follow-up email needed for client: [{}]", currentState().email());
    return effects()
        .updateState(currentState().followUpRequired())
        .pause()
        .thenReply("Follow-up email sent");
  }

  public ReadOnlyEffect<ProspectState.Status> status() {
    return effects().reply(currentState().status());
  }

}