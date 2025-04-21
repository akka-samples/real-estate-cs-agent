package realestate.api;

import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.HttpResponses;
import realestate.application.ClientInfoEntity;
import realestate.application.ProspectProcessingWorkflow;

import java.util.concurrent.CompletionStage;

import static java.util.Objects.requireNonNull;

/**
 * This is a public API that allows to simulate the arrival of a new email.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
@HttpEndpoint("/emails")
public class EmailEndpoint {

  private ComponentClient componentClient;

  public record NewEmailReq(String sender, String subject, String content) {}

  public EmailEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  @Post
  public CompletionStage<HttpResponse> newEmail(NewEmailReq newEmailReq) {
    if (newEmailReq.subject == null || newEmailReq.subject.isEmpty())
      throw new IllegalArgumentException("subject cannot be empty");
    if (newEmailReq.content == null || newEmailReq.content.isEmpty())
      throw new IllegalArgumentException("content cannot be empty");

    return componentClient.forWorkflow(newEmailReq.sender())
        .method(ProspectProcessingWorkflow::processNewEmail)
        .invokeAsync(new ProspectProcessingWorkflow.ProcessMessage(newEmailReq.sender, newEmailReq.subject(), newEmailReq.content()))
        .thenApply(__ -> HttpResponses.accepted());
  }

  @Get("/{id}")
  public CompletionStage<ClientInfoEntity.ClientState> getEntity(String id) {
    return componentClient.forEventSourcedEntity(id)
        .method(ClientInfoEntity::get)
        .invokeAsync();
  }
}
