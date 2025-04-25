package realestate.application;

import akka.Done;
import akka.javasdk.annotations.ComponentId;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.timer.TimerScheduler;
import akka.javasdk.workflow.Workflow;
import realestate.domain.CustomerServiceAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import realestate.domain.ProspectState;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalUnit;
import java.util.concurrent.CompletableFuture;

import static realestate.application.ProspectProcessingWorkflow.WorkflowSteps.WAITING_REPLY;

@ComponentId("prospect-processing-workflow")
public class ProspectProcessingWorkflow extends Workflow<ProspectState> {

  private final Logger logger = LoggerFactory.getLogger(getClass());

  private final TimerScheduler timerScheduler;
  private final ComponentClient componentClient;

  enum WorkflowSteps {
    COLLECTING,
    WAITING_REPLY
  }

  private CustomerServiceAgent propertyAgentTools;

  public ProspectProcessingWorkflow(
      CustomerServiceAgent propertyAgentTools,
      TimerScheduler timerScheduler,
      ComponentClient componentClient) {
    this.propertyAgentTools = propertyAgentTools;
    this.timerScheduler = timerScheduler;
    this.componentClient = componentClient;
  }


  @Override
  public WorkflowDef<ProspectState> definition() {

    Step collectingClientDetails =
        step(WorkflowSteps.COLLECTING.name())
            .call(() -> {
              if (currentState().status() == ProspectState.Status.COLLECT
                  || currentState().status() == ProspectState.Status.WAITING_REPLY) {
                currentState().unreadMessages().forEach(m -> logger.debug("Processing pending email: " + m));

                try {
                  var result = propertyAgentTools.processCustomerMessage(currentState().email(), currentState().pastMessages(), currentState().unreadMessages());
                  result.toolExecutions().forEach(e -> logger.debug("Tool execution: [{}]", e));
                  return result.content();
                } catch (Exception e) {
                  logger.error("Failed to extract property info", e);
                  return e.getMessage();
                }
              } else {
                return "unexpected status";
              }
            })
            .andThen(String.class, msg -> {
              logger.debug("Current status: [{}], processing from AI: [{}]", currentState().status(), msg);

              return switch(msg) {
                case "WAIT_REPLY" ->
                    effects()
                      .updateState(currentState().waitingReply().withAiMessage(msg))
                      .transitionTo(WAITING_REPLY.name());
                case "ALL_INFO_COLLECTED" -> {
                  logger.info("All info collected for client: [{}]", currentState().email());
                  yield effects()
                      .updateState(currentState().closed().withAiMessage(msg))
                      .end();
                }
                default -> {
                  logger.error("Could not process message from AI: [{}]", msg);
                  yield effects()
                      .updateState(currentState().withAiMessage(msg))
                      .pause();
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
                  Duration.of(1, ChronoUnit.MINUTES),
                  call);
              logger.debug("Created timer for follow up. timerId={} ", timerId);
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

}