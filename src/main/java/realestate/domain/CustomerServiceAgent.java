package realestate.domain;

import akka.javasdk.client.ComponentClient;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore;
import realestate.application.ClientInfoEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import realestate.application.EmailClient;
import realestate.application.ProspectProcessingWorkflow;

import java.util.ArrayList;
import java.util.List;

import static java.util.Objects.requireNonNull;

public class CustomerServiceAgent {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final ChatLanguageModel chatModel;
    private final ComponentClient componentClient;
    private final EmailClient emailClient;


    public CustomerServiceAgent(ChatLanguageModel chatModel, ComponentClient componentClient, EmailClient emailClient) {
        this.chatModel = chatModel;
        this.componentClient = componentClient;
        this.emailClient = emailClient;
    }

    interface PropertyAgent {
        @SystemMessage(
            """
            <instructions>
            You are a customer service agent for a real estate company processing incoming emails from customers who are looking to rent or buy properties.
            Your job is to collect the following information:
            - Full name
            - Phone number
            - Email address
            - City and country of interest
            - Type of property (apartment or house)
            - Transaction type (buy or rent)
            
            Make sure to extract information not only from the email content but also from the subject line — important details like transaction type or location may be mentioned there.
            Unless the customer says otherwise, you should assume the email address they are using (in the 'From' field) is their valid contact email.
            Only send an email to the customer if you cannot derive the information from their emails.
            If sending an email, ask ONLY for the missing information. Do NOT ask for anything already provided.
            If the last step was sending an email, don't do anything and just wait for customer to reply.
            When you have all the information, use the tools provided to save the customer information.
            Reply only with: WAIT_REPLY or ALL_INFO_COLLECTED
            </instructions>
            """
        )
        Result<String> processEmails(String emails);

    }

    // Tool implementations
    @Tool("Send email to customer. Use only when customer has not provided all the required information.")
    public String sendEmail(@P("destination email") String email, @P("subject of the email to reply to") String subject, @P("content of the email") String content) {
        emailClient.sendEmail(email, subject, content);
        return "Email was sent to " + email + ". Wait for a reply.";
    }

    @Tool("Save customer information. Use **ONLY** if all required information is collected")
    public String saveCustomerInformation(
        @P("customer's full name") String name,
        @P("customer's email address") String email,
        @P("customer's phone number") String phoneNumber,
        @P("city / country of interest") String location,
        @P("type of property (apartment or house)") String propertyType,
        @P("transaction type (buy or rent)") String transactionType) {

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

    // convert messages from state into format for chat memory
    private List<ChatMessage> convertMessages(List<ProspectState.Message> history) {
        List<ChatMessage> messages = new ArrayList<>();
        for (ProspectState.Message message : history) {
            if (message.senderType() == ProspectState.SenderType.USER) {
                messages.add(new UserMessage(message.toString()));
            } else {
                messages.add(new AiMessage(message.toString()));
            }
        }
        return messages;
    }

    // Extract property information from email
    public Result<String> processCustomerMessage(String sessionId,
                                                 List<ProspectState.Message> history,
                                                 List<ProspectState.Message> emailContent) {
        try {
            var chatMemoryStore = new InMemoryChatMemoryStore();
            chatMemoryStore.updateMessages(sessionId, convertMessages(history));
            var messageMemory = MessageWindowChatMemory.builder()
                .maxMessages(1000)
                .chatMemoryStore(chatMemoryStore)
                .build();

            var propertyAgent = AiServices.builder(PropertyAgent.class)
                .chatLanguageModel(chatModel)
                .tools(this)
                .chatMemory(messageMemory)
                .hallucinatedToolNameStrategy(toolReq -> {
                    logger.warn("Hallucinated tool: " + toolReq);
                    return new ToolExecutionResultMessage(toolReq.id(), toolReq.name(), "The tool '" + toolReq.name() + "' does not exist.");
                })
                .build();

            var unreadMsgs = emailContent.stream().map(ProspectState.Message::toString).reduce("", String::concat);
            return propertyAgent.processEmails(unreadMsgs);
        } catch (Exception e) {
            logger.error("Error processing emails", e);
            return Result.<String>builder().build();
        }
    }
}