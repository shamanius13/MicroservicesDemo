package ak83.util.http;

import java.time.ZonedDateTime;

import lombok.*;
import org.springframework.http.HttpStatus;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class HttpErrorInfo {

  private String error;
  private String message;
  private String path;
  private int status;
  private ZonedDateTime timestamp;

  public HttpErrorInfo(String message, String path, HttpStatus status) {
    this.error = status.getReasonPhrase();
    this.message = message;
    this.path = path;
    this.status = status.value();
    this.timestamp = ZonedDateTime.now();
  }

}
