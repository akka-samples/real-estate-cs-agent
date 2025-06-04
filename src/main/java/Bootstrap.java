import akka.javasdk.DependencyProvider;
import akka.javasdk.ServiceSetup;
import akka.javasdk.annotations.Setup;
//import dev.langchain4j.model.ollama.OllamaChatModel;
import realestate.application.EmailClient;

@Setup
public class Bootstrap implements ServiceSetup {

  public Bootstrap() {

    // forcing OpenAI API key from environment variable
    var apiKey = System.getenv("OPENAI_API_KEY");
    if (apiKey == null || apiKey.isEmpty())
      throw new IllegalArgumentException("Requires an OpenAI API key to be set in the environment variable OPENAI_API_KEY");

  }

  @Override
  public DependencyProvider createDependencyProvider() {
    return new DependencyProvider() {

      @Override
      public <T> T getDependency(Class<T> aClass) {
        if (aClass.equals(EmailClient.class)) {
          return (T) new EmailClient();
        }
        return null;
      }
    };
  }

}
