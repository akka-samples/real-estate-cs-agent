package realestate.application;

import akka.javasdk.agent.Agent;
import akka.javasdk.annotations.ComponentId;
import akka.javasdk.annotations.FunctionTool;
import akka.javasdk.client.ComponentClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import realestate.domain.ProspectState;

import java.util.List;

import static java.util.Objects.requireNonNull;

@ComponentId("customer-service-agent")
public class CustomerServiceAgent extends Agent {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final ComponentClient componentClient;
    private final EmailClient emailClient;


    public CustomerServiceAgent(ComponentClient componentClient, EmailClient emailClient) {
        this.componentClient = componentClient;
        this.emailClient = emailClient;
    }

    private final String SYSTEM_PROMPT =
        """
        <instructions>
        You are a customer service agent for a real estate company processing incoming emails
        from customers who are looking to rent or buy properties.
        Your job is to collect the following information:
        - Full name
        - Phone number
        - Email address
        - City and country of interest
        - Type of property (apartment or house)
        - Transaction type (buy or rent)
        
        Make sure to extract information not only from the email content but also from the
        subject line — important details like transaction type or location may be mentioned there.
        Unless the customer says otherwise, you should assume the email address they are
        using (in the 'From' field) is their valid contact email.
        Only send an email to the customer if you cannot derive the information from their emails.
        If sending an email, ask ONLY for the missing information. Do NOT ask for anything already provided.
        If the last step was sending an email, don't do anything and just wait for customer to reply.
        When you have all the information, use the tools provided to save the customer information.
        Reply only with: WAIT_REPLY or ALL_INFO_COLLECTED
        </instructions>
        """;

    public record ProcessEmailsCmd(List<ProspectState.Message> emailContent) {}




    public Effect<String> processEmails(ProcessEmailsCmd cmd) {
        var unreadMsgs = cmd.emailContent.stream().map(ProspectState.Message::toString).reduce("", String::concat);

        return effects()
            .systemMessage(SYSTEM_PROMPT)
            .userMessage(unreadMsgs)
            .thenReply();
    }


    // Tool implementations
    @FunctionTool(
        name = "send-email-customer",
        description = "Send email to customer. Use only when customer has not provided all the required information.")
    public String sendEmail(String email, String subject, String content) {
        emailClient.sendEmail(email, subject, content);
        return "Email was sent to " + email + ". Wait for a reply.";
    }

    @FunctionTool(
        name = "save-customer-info",
        description = "Save customer information. Use **ONLY** if all required information is collected")
    public String saveCustomerInformation(
        String name,
        String email,
        String phoneNumber,
        String location,
        String propertyType,
        String transactionType) {

        try {
            requireNonNull(name, "Name cannot be null");
            requireNonNull(email, "Email cannot be null");

            logger.info("Saving customer information: name={}, email={}, phone={}, location={}, propertyType={}, transactionType={}",
                name, email, phoneNumber, location, propertyType, transactionType);

            // Save information to ClientInfoEntity
            componentClient.forEventSourcedEntity(email)
                .method(ClientInfoEntity::saveClientInfo)
                .invoke(
                    new ClientInfoEntity.SaveInfoCmd(
                        name,
                        email,
                        phoneNumber,
                        ClientInfoEntity.PropertyDetails.of(location, propertyType, transactionType)
                ));

            return "Successfully saved customer information for " + name;
        } catch (Exception e) {
            logger.error("Error saving customer information", e);
            return "Failed to save customer information: " + e.getMessage();
        }
    }
}