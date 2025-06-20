package realestate.domain;

import akka.javasdk.annotations.TypeName;

sealed public interface ClientEvent {
  @TypeName("client-info-saved")
  record ClientInfoSaved(String name, String email, String phone) implements ClientEvent { }
}
