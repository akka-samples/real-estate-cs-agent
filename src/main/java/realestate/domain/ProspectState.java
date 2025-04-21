package realestate.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;


public record ProspectState(Status status, String name, String email, List<Message> pastMessages, List<Message> unreadMessages, long lastUpdated) {

  public enum Status {
    COLLECT,
    PROCESSING,
    WAITING_REPLY,
    CLOSED,
    FOLLOW_UP
  }

  public enum SenderType {
    USER,
    ASSISTANT
  }

  public record Message(SenderType senderType, String sender, String subject, String content) {

    public String toString() {
      return "<from>" + sender + "</from>" +
          "\n<subject>" + subject + "</subject>" +
          "\n<content>" + content + "</content>" +
          "\n\n";
    }

    public static Message AiMessage(String content) {
      return new Message(SenderType.ASSISTANT, "agent@example.com", "AI Response", content);
    }

    public static Message UserMessage(String sender, String subject, String content) {
      return new Message(SenderType.USER, sender, subject, content);
    }
  }


  public ProspectState {
    pastMessages = pastMessages != null ? new ArrayList<>(pastMessages) : new ArrayList<>();
    unreadMessages = unreadMessages != null ? new ArrayList<>(unreadMessages) : new ArrayList<>();
  }

  public ProspectState waitingReply() {
    return new ProspectState(Status.WAITING_REPLY, name, email, pastMessages, unreadMessages, System.currentTimeMillis());
  }

  public ProspectState closed() {
    return new ProspectState(Status.CLOSED, name, email, pastMessages, unreadMessages, System.currentTimeMillis());
  }

  public ProspectState followUpRequired() {
    return new ProspectState(Status.FOLLOW_UP, name, email, pastMessages, unreadMessages, System.currentTimeMillis());
  }

  public ProspectState withAiMessage(String content) {
    var updatedList = new ArrayList<>(pastMessages());
    updatedList.addAll(unreadMessages);
    updatedList.add(Message.AiMessage(content));
    return new ProspectState(status, name, email, updatedList, Collections.emptyList(), System.currentTimeMillis());
  }

  public ProspectState addUnreadMessage(Message message) {
    var updatedList = new ArrayList<>(pastMessages());
    updatedList.add(message);
    return new ProspectState(status, name, email, pastMessages, updatedList, System.currentTimeMillis());
  }

  public ProspectState withEmail(String email) {
    return new ProspectState(status, name, email, pastMessages, unreadMessages, System.currentTimeMillis());
  }

  public static final ProspectState EMPTY =
      new ProspectState(Status.COLLECT, "", "", new ArrayList<>(), new ArrayList<>(), 0L);
}