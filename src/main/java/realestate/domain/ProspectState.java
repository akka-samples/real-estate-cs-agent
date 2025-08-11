package realestate.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;


public record ProspectState(Status status, String email, List<Message> unreadMessages, long lastUpdated, Optional<PropertyDetails> details) {

  public enum Status {
    COLLECT,
    PROCESSING,
    WAITING_REPLY,
    CLOSED,
    FOLLOW_UP,
    ERROR
  }

  public record PropertyDetails(String location, String type, String transactionType) {}

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

    public static Message UserMessage(String sender, String subject, String content) {
      return new Message(SenderType.USER, sender, subject, content);
    }
  }


  public ProspectState {
    unreadMessages = unreadMessages != null ? new ArrayList<>(unreadMessages) : new ArrayList<>();
  }

  public ProspectState(Status status, String email, List<Message> unreadMessages, long lastUpdated) {
    this(status, email, unreadMessages, lastUpdated, Optional.empty());
  }

  public boolean isWaitingReply() {
    return status == Status.WAITING_REPLY;
  }

  public ProspectState waitingReply() {
    return new ProspectState(Status.WAITING_REPLY, email, unreadMessages, System.currentTimeMillis());
  }

  public ProspectState closed() {
    return new ProspectState(Status.CLOSED, email, unreadMessages, System.currentTimeMillis());
  }

  public ProspectState error() {
    return new ProspectState(Status.ERROR, email, unreadMessages, System.currentTimeMillis());
  }


  public ProspectState followUpRequired() {
    return new ProspectState(Status.FOLLOW_UP, email, unreadMessages, System.currentTimeMillis());
  }

  public ProspectState addUnreadMessage(Message message) {
    var updatedList = new ArrayList<>(unreadMessages);
    updatedList.add(message);
    return new ProspectState(status, email, updatedList, System.currentTimeMillis());
  }

  public ProspectState withEmail(String email) {
    return new ProspectState(status, email, unreadMessages, System.currentTimeMillis());
  }

  public ProspectState withDetails(String location, String type, String transactionType) {
    return new ProspectState(status, email, unreadMessages, System.currentTimeMillis(), Optional.of(new PropertyDetails(location, type, transactionType)));
  }

  public static final ProspectState EMPTY =
      new ProspectState(Status.COLLECT, "", new ArrayList<>(), 0L);
}