import akka.javasdk.DependencyProvider;
import akka.javasdk.ServiceSetup;
import akka.javasdk.annotations.Setup;
import akka.javasdk.client.ComponentClient;
//import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiChatModelName;
import realestate.application.EmailClient;
import realestate.domain.CustomerServiceAgent;

@Setup
public class ServiceSetupImpl implements ServiceSetup {

  //private final ChatLanguageModel model;
  private final ComponentClient componentClient;
  private final OpenAiChatModel model;

  private static String MODEL_NAME = "llama3.2"; // try other local ollama model names
  private static String BASE_URL = "http://localhost:11434"; // local ollama base url

  public ServiceSetupImpl(ComponentClient componentClient) {
    this.componentClient = componentClient;
    /*this.model = OllamaChatModel.builder()
        .baseUrl(BASE_URL)
        .modelName(MODEL_NAME)
        .logRequests(true)
        .logResponses(true)
        .temperature(0.1)
        .build();*/

    // forcing OpenAI API key from environment variable
    var apiKey = System.getenv("OPENAI_API_KEY");
    if (apiKey == null || apiKey.isEmpty())
      throw new IllegalArgumentException("Requires an OpenAI API key to be set in the environment variable OPENAI_API_KEY");

    this.model = OpenAiChatModel.builder()
        .apiKey(System.getenv("OPENAI_API_KEY"))
        .modelName(OpenAiChatModelName.GPT_4_O_MINI)
        .temperature(0.0)
        .logResponses(true)
        .logRequests(true)
        .build();

  }

  @Override
  public DependencyProvider createDependencyProvider() {
    return new DependencyProvider() {

      @Override
      public <T> T getDependency(Class<T> aClass) {
        if (aClass.equals(CustomerServiceAgent.class)) {
          return (T) new CustomerServiceAgent(model, componentClient, new EmailClient());
        } else if (aClass.equals(EmailClient.class)) {
          return (T) new EmailClient();
        }
        return null;
      }
    };
  }

}
