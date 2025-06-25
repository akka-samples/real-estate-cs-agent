package realestate;

import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestModelProvider;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import akka.javasdk.testkit.TestKitSupport;
import realestate.application.CustomerServiceAgent;
import realestate.application.ProspectProcessingWorkflow;
import realestate.domain.ProspectState;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static realestate.domain.ProspectState.Status.*;

/**
 * Integration tests for the Real Estate Customer Service Agent application.
 * Tests the complete workflow from email processing to customer information collection.
 */
public class IntegrationTest extends TestKitSupport {

  private final TestModelProvider testModelProvider = new TestModelProvider();

  @Override
  protected TestKit.Settings testKitSettings() {
    return TestKit.Settings.DEFAULT
        .withAdditionalConfig("akka.javasdk.agent.openai.api-key = n/a")
        .withModelProvider(CustomerServiceAgent.class, testModelProvider)
        .withAdditionalConfig("realestate.follow-up.timer = 5s");
  }

  @BeforeEach
  void setUp() {
    // Reset the test model provider before each test
    testModelProvider.reset();
  }

  private void assertWorkflowStatus(String workflowId, ProspectState.Status expectedStatus) {
    // Wait for workflow to transition to waiting state
    Awaitility.await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> {
          var status = componentClient
              .forWorkflow(workflowId)
              .method(ProspectProcessingWorkflow::status)
              .invokeAsync()
              .toCompletableFuture()
              .get(10, TimeUnit.SECONDS);

          // Verify workflow is in waiting state
          assertEquals(expectedStatus, status);
        });
  }

  @Test
  public void shouldCompleteInquiryWhenAllInformationProvidedInFirstEmail() throws Exception {
    // Given: Mock LLM response indicating all information is collected - match XML format
    testModelProvider
        .whenMessage(message -> message.contains("complete@inquiry.com"))
        .reply("ALL_INFO_COLLECTED");

    String customerEmail = "complete@inquiry.com";

    // When: Process a complete email with all required information
    var processMessageCmd = new ProspectProcessingWorkflow.ProcessMessage(
        customerEmail,
        "Looking to rent T2 in Porto",
        "Hello, I am John Doe looking to rent a T2 in Porto. My phone number is 911111111. I need 2 bedrooms and my budget is 1200 euros."
    );

    var response = componentClient
        .forWorkflow(customerEmail)
        .method(ProspectProcessingWorkflow::processNewEmail)
        .invokeAsync(processMessageCmd)
        .toCompletableFuture()
        .get(10, TimeUnit.SECONDS);

    // Then: Workflow should start processing
    assertEquals("Processing started", response);

    assertWorkflowStatus(customerEmail, CLOSED);
  }

  @Test
  public void shouldRequestFollowUpWhenInformationIncomplete() throws Exception {
    String customerEmail = "follow@up.com";
    // Given: Mock LLM response indicating more information is needed - match XML format
    testModelProvider
        .whenMessage(message -> message.contains(customerEmail))
        .reply("WAIT_REPLY");

    // When: Process an incomplete email
    var processMessageCmd = new ProspectProcessingWorkflow.ProcessMessage(
        customerEmail,
        "Looking for apartment",
        "Hi, I'm looking for an apartment to rent in Porto."
    );

    var response = componentClient
        .forWorkflow(customerEmail)
        .method(ProspectProcessingWorkflow::processNewEmail)
        .invoke(processMessageCmd);

    // Then: Workflow should start and wait for reply
    assertEquals("Processing started", response);
  }

  @Test
  public void shouldCompleteAfterCustomerProvidesAdditionalInformation() throws Exception {
    // Given: First incomplete inquiry - match the XML format that Message.toString() produces
    var customerEmail = "john@doe.com";
    testModelProvider
        .whenMessage(message -> message.contains("john@doe.com") & !message.contains("123456789"))
        .reply("WAIT_REPLY");

    // Process initial incomplete email
    var initialMessage = new ProspectProcessingWorkflow.ProcessMessage(
        customerEmail,
        "Looking for apartment",
        "Hi, I'm looking for an apartment to rent."
    );

    componentClient
        .forWorkflow(customerEmail)
        .method(ProspectProcessingWorkflow::processNewEmail)
        .invoke(initialMessage);

    assertWorkflowStatus(customerEmail, WAITING_REPLY);

    // Given: Mock response for complete information - match the XML format for follow-up message
    testModelProvider
        .whenMessage(message -> message.contains(customerEmail) && message.contains("123456789"))
        .reply("ALL_INFO_COLLECTED");

    // When: Customer provides additional information
    var followUpMessage = new ProspectProcessingWorkflow.ProcessMessage(
        customerEmail,
        "Re: Looking for apartment",
        "My name is John Doe and my phone number is 123456789. I'm looking for a 2-bedroom apartment with a budget of 1500 euros."
    );

    var followUpResponse = componentClient
        .forWorkflow(customerEmail)
        .method(ProspectProcessingWorkflow::processNewEmail)
        .invoke(followUpMessage);

    // Then: Workflow should complete successfully
    assertEquals("Processing started", followUpResponse);

    assertWorkflowStatus(customerEmail, CLOSED);
  }

  @Test
  public void shouldTriggerFollowUpAfterTimeout() throws Exception {
    var customerEmail = "timeout@test.com";

    // Given: Mock LLM response indicating wait for reply - match XML format
    testModelProvider
        .whenMessage(message -> message.contains(customerEmail))
        .reply("WAIT_REPLY");

    // When: Process email that requires follow-up
    var processMessageCmd = new ProspectProcessingWorkflow.ProcessMessage(
        customerEmail,
        "Property inquiry",
        "I'm interested in property listings."
    );

    componentClient
        .forWorkflow(customerEmail)
        .method(ProspectProcessingWorkflow::processNewEmail)
        .invoke(processMessageCmd);

    assertWorkflowStatus(customerEmail, WAITING_REPLY);

    // Then: Wait for follow-up timer to trigger (1 minute in real app, but might be shorter in tests)
    assertWorkflowStatus(customerEmail, FOLLOW_UP);
  }

  @Test
  public void shouldHandleErrorScenarios() throws Exception {
    // Given: Mock LLM response that causes an error - match XML format
    testModelProvider
        .whenMessage(message -> message.contains("error@test.com"))
        .failWith(new RuntimeException("Simulated error"));

    String customerEmail = "error@test.com";

    // When: Process email that will cause an error
    var processMessageCmd = new ProspectProcessingWorkflow.ProcessMessage(
        customerEmail,
        "Error scenario",
        "This should trigger an error scenario in the workflow."
    );

    var response = componentClient
        .forWorkflow(customerEmail)
        .method(ProspectProcessingWorkflow::processNewEmail)
        .invoke(processMessageCmd);

    assertEquals("Processing started", response);

    assertWorkflowStatus(customerEmail, ERROR);
  }


  @Test
  public void shouldHandleMultipleCustomersSimultaneously() throws Exception {
    var customer1 = "customer1@test.com";
    var customer2 = "customer2@test.com";

    // Given: Different responses for different customers - match XML format
    testModelProvider
        .whenMessage(message -> message.contains(customer1) && message.contains("111-1111"))
        .reply("ALL_INFO_COLLECTED");

    testModelProvider
        .whenMessage(message -> message.contains(customer2))
        .reply("WAIT_REPLY");

    // When: Process emails from multiple customers
    var customer1Message = new ProspectProcessingWorkflow.ProcessMessage(
        customer1,
        "Complete inquiry",
        "Hi, I'm Customer One, phone 111-1111. I need a 3-bedroom house, budget 2000 euros."
    );

    var customer2Message = new ProspectProcessingWorkflow.ProcessMessage(
        customer2,
        "Incomplete inquiry",
        "I'm looking for an apartment."
    );

    // Process both simultaneously
    var response1 = componentClient
        .forWorkflow(customer1)
        .method(ProspectProcessingWorkflow::processNewEmail)
        .invoke(customer1Message);

    var response2 = componentClient
        .forWorkflow(customer2)
        .method(ProspectProcessingWorkflow::processNewEmail)
        .invoke(customer2Message);

    // Then: Both should be processed independently
    assertEquals("Processing started", response1);
    assertEquals("Processing started", response2);

    assertWorkflowStatus(customer1, CLOSED);
    assertWorkflowStatus(customer2, WAITING_REPLY);
  }
}