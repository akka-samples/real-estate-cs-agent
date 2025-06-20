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
import realestate.domain.ClientState;

/**
 * This is a public API that allows to simulate the arrival of a new email.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
@HttpEndpoint("/emails")
public class EmailEndpoint {

  private final ComponentClient componentClient;

  public record NewEmailReq(String sender, String subject, String content) {}

  public EmailEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  @Post
  public HttpResponse newEmail(NewEmailReq newEmailReq) {
    if (newEmailReq.subject == null || newEmailReq.subject.isEmpty())
      throw new IllegalArgumentException("subject cannot be empty");
    if (newEmailReq.content == null || newEmailReq.content.isEmpty())
      throw new IllegalArgumentException("content cannot be empty");

    componentClient.forWorkflow(newEmailReq.sender())
        .method(ProspectProcessingWorkflow::processNewEmail)
        .invoke(new ProspectProcessingWorkflow.ProcessMessage(newEmailReq.sender, newEmailReq.subject(), newEmailReq.content()));

  return HttpResponses.accepted();
  }

  @Get("/{id}")
  public ClientState getEntity(String id) {
    return componentClient.forEventSourcedEntity(id)
        .method(ClientInfoEntity::get)
        .invoke();
  }
}
