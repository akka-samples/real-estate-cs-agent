package realestate.application;

import akka.Done;
import akka.javasdk.annotations.ComponentId;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.timer.TimerScheduler;
import akka.javasdk.workflow.Workflow;
import com.typesafe.config.Config;
import realestate.domain.CustomerServiceAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import realestate.domain.ProspectState;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

import static realestate.application.ProspectProcessingWorkflow.WorkflowSteps.WAITING_REPLY;

@ComponentId("prospect-processing-workflow")
public class ProspectProcessingWorkflow extends Workflow<ProspectState> {

  private final Logger logger = LoggerFactory.getLogger(getClass());

  private final TimerScheduler timerScheduler;
  private final ComponentClient componentClient;
  private final Duration followUpTimer;

  enum WorkflowSteps {
    COLLECTING,
    WAITING_REPLY
  }


  public ProspectProcessingWorkflow(
      TimerScheduler timerScheduler,
      ComponentClient componentClient,
      Config config) {
    this.timerScheduler = timerScheduler;
    this.componentClient = componentClient;
    this.followUpTimer = config.getDuration("realestate.follow-up.timer");
  }


  @Override
  public WorkflowDef<ProspectState> definition() {

    Step collectingClientDetails =
        step(WorkflowSteps.COLLECTING.name())
            .call(() -> {
              if (currentState().status() != ProspectState.Status.CLOSED
                  && currentState().status() != ProspectState.Status.ERROR) {
                currentState().unreadMessages().forEach(m -> logger.debug("Processing pending email: " + m));

                return componentClient
                    .forAgent()
                    .inSession(commandContext().workflowId())
                    .method(CustomerServiceAgent::processEmails)
                    .invoke(new CustomerServiceAgent.ProcessEmailsCmd(currentState().unreadMessages()));

              } else {
                return "unexpected status " + currentState().status();
              }
            })
            .andThen(String.class, msg -> {
              logger.debug("Current status: [{}], processing from AI: [{}]", currentState().status(), msg);

              return switch(msg) {
                case "WAIT_REPLY" ->
                    effects()
                      .updateState(currentState().waitingReply())
                      .transitionTo(WAITING_REPLY.name());
                case "ALL_INFO_COLLECTED" -> {
                  logger.info("All info collected for client: [{}]", currentState().email());
                  yield effects()
                      .updateState(currentState().closed())
                      .end();
                }
                default -> {
                  logger.error("Could not process message from AI: [{}]", msg);
                  yield effects().pause();
                }
              };
            });

    Step waitingReply =
        step(WAITING_REPLY.name())
            .call(() -> {
              // schedule follow-up email after 1 minute
              var call = componentClient.forWorkflow(commandContext().workflowId()).method(ProspectProcessingWorkflow::followUp).deferred();
              var timerId = "follow-up-" + commandContext().workflowId();
              timerScheduler.createSingleTimer(
                  timerId,
                  followUpTimer,
                  call);
              logger.debug("Created timer for follow up in {}. timerId={} ", followUpTimer, timerId);
              return Done.getInstance();
            })
            .andThen(Done.class, __ -> effects().pause());

    Step errorStep = step("error")
        .call(() -> {
              logger.error("Workflow for for customer [{}] failed", currentState().email());
              return Done.done();
            }
        )
        .andThen(Done.class, __ ->
            effects()
              .updateState(currentState().error())
              .end());


    return workflow()
        .addStep(collectingClientDetails)
        .addStep(waitingReply)
        .addStep(errorStep)
        .defaultStepRecoverStrategy(maxRetries(2).failoverTo("error"))
        .defaultStepTimeout(Duration.ofMinutes(1));
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

    // delete existing timer if it exists since we have a reply now
    if (currentState() != null)
      timerScheduler.delete("follow-up-" + currentState().email());

    return effects()
        .updateState(updatedState)
        .transitionTo(WorkflowSteps.COLLECTING.name())
        .thenReply("Processing started");
  }

  public Effect<String> followUp() {
    if (currentState() == null || currentState().status() != ProspectState.Status.WAITING_REPLY) {
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