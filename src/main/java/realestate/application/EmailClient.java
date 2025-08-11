package realestate.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dummy email client for demonstration purpose
 */
public class EmailClient {

  private static final Logger logger = LoggerFactory.getLogger(EmailClient.class.getName());

  public void sendEmail(String to, String subject, String body) {
    logger.info("Sent email {to: {}, subject: {}, context: {}", to, subject, body);
  }
}
