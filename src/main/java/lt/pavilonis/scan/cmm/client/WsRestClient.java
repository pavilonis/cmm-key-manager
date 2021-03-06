package lt.pavilonis.scan.cmm.client;

import com.google.common.collect.ImmutableMap;
import javafx.application.Platform;
import lt.pavilonis.scan.cmm.client.ui.classusage.ScanLogBrief;
import lt.pavilonis.scan.cmm.client.ui.keylog.Key;
import lt.pavilonis.scan.cmm.client.ui.keylog.KeyAction;
import lt.pavilonis.scan.cmm.client.ui.keylog.KeyLogFilter;
import lt.pavilonis.scan.cmm.client.ui.scanlog.ScanLog;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.slf4j.LoggerFactory.getLogger;

@Service
public class WsRestClient {
   private static final Logger LOG = getLogger(WsRestClient.class.getSimpleName());
   private static final String SEGMENT_KEYS = "keys";
   private static final String SEGMENT_LOG = "log";
   private static final String SEGMENT_ROLES = "roles";
   private static final String SEGMENT_SCANLOG = "scanlog";
   private static final String SEGMENT_LASTSEEN = "lastseen";

   @Value(("${api.uri.base}"))
   private String baseUri;

   @Value(("${scanner.id}"))
   private String scannerId;

   @Autowired
   private RestTemplate restTemplate;

   @Autowired
   private MessageSourceAdapter messages;

   private String lastErrorMessage;

   public void writeScanLog(String cardCode, Consumer<Optional<ScanLog>> consumer) {

      URI uri = uri(SEGMENT_SCANLOG, scannerId, cardCode);

      LOG.info("Sending scanLog POST request [scannerId={}, cardCode={}]", scannerId, cardCode);

      request(uri, HttpMethod.POST, ScanLog.class, consumer);
   }

   public void allActiveKeys(String keyNumber, Consumer<Optional<Key[]>> consumer) {
      Map<String, String> args = new HashMap<>();
      args.put("scannerId", scannerId);
      if (StringUtils.isNotBlank(keyNumber)) {
         args.put("keyNumber", keyNumber);
      }

      request(uri(args, SEGMENT_KEYS), HttpMethod.GET, Key[].class, consumer);
   }

   public void userKeysAssigned(String cardCode, Consumer<Optional<Key[]>> consumer) {
      Map<String, String> params = ImmutableMap.of("scannerId", scannerId, "cardCode", cardCode);
      request(uri(params, SEGMENT_KEYS), HttpMethod.GET, Key[].class, consumer);
   }

   public void assignKey(String cardCode, int keyNumber, Consumer<Optional<Key>> consumer) {
      URI uri = uri(SEGMENT_KEYS, scannerId, String.valueOf(keyNumber), cardCode);
      request(uri, HttpMethod.POST, Key.class, consumer);
   }

   public void returnKey(int keyNumber, Consumer<Optional<Key>> consumer) {
      URI uri = uri(SEGMENT_KEYS, scannerId, String.valueOf(keyNumber));
      request(uri, HttpMethod.DELETE, Key.class, consumer);
   }

   public void keyLog(KeyLogFilter filter, Consumer<Optional<Key[]>> consumer) {

      Map<String, String> params = new HashMap<>();
      params.put("scannerId", scannerId);
      params.put("periodStart", DateTimeFormatter.ISO_LOCAL_DATE.format(filter.getPeriodStart()));
      params.put("periodEnd", DateTimeFormatter.ISO_LOCAL_DATE.format(filter.getPeriodEnd()));

      if (StringUtils.isNotBlank(filter.getKeyNumber())) {
         params.put("keyNumber", filter.getKeyNumber());
      }
      if (filter.getKeyAction() != KeyAction.ALL) {
         params.put("keyAction", filter.getKeyAction().name());
      }
      if (StringUtils.isNotBlank(filter.getName())) {
         params.put("nameLike", filter.getName());
      }

      URI uri = uri(params, SEGMENT_KEYS, SEGMENT_LOG);
      request(uri, HttpMethod.GET, Key[].class, consumer);
   }

   public void classroomUsage(String text, Consumer<Optional<ScanLogBrief[]>> consumer) {
      Map<String, String> params = new HashMap<>();

      if (StringUtils.isNotBlank(text)) {
         params.put("text", text);
      }

      request(uri(params, SEGMENT_SCANLOG, SEGMENT_LASTSEEN), HttpMethod.GET, ScanLogBrief[].class, consumer);
   }

   public void loadRoles(Consumer<Optional<String[]>> rolesConsumer) {
      request(uri(SEGMENT_ROLES), HttpMethod.GET, String[].class, rolesConsumer);
   }

   public Optional<String> getLastErrorMessage() {
      return Optional.ofNullable(lastErrorMessage);
   }

   private URI uri(String... segments) {
      return uri(Collections.emptyMap(), segments);
   }

   private URI uri(Map<String, String> params, String... segments) {
      LinkedMultiValueMap<String, String> paramMultiMap = new LinkedMultiValueMap<>();
      params.forEach(paramMultiMap::add);

      return UriComponentsBuilder.fromUriString(baseUri)
            .pathSegment(segments)
            .queryParams(paramMultiMap)
            .build()
            .toUri();
   }

   public <T> void request(URI uri, HttpMethod requestMethod, Class<T> responseType, Consumer<Optional<T>> consumer) {
      new BackgroundTask<>(() -> {
         ResponseEntity<T> exchange = tryRequest(uri, requestMethod, responseType);
         App.clearWarning();
         Platform.runLater(() -> consumer.accept(Optional.ofNullable(exchange == null ? null : exchange.getBody())));
      });
   }

   private <T> ResponseEntity<T> tryRequest(URI uri, HttpMethod requestMethod, Class<T> responseType) {
      try {
         ResponseEntity<T> response = restTemplate.exchange(uri, requestMethod, null, responseType);
         this.lastErrorMessage = null;
         return response;
      } catch (HttpClientErrorException httpErr) {

         switch (httpErr.getStatusCode()) {
            case NOT_FOUND:
               this.lastErrorMessage = messages.get(this, "resourceNotFound");
               break;
            case CONFLICT:
               this.lastErrorMessage = messages.get(this, "requestConflict");
               break;
            default:
               this.lastErrorMessage = httpErr.getMessage();
         }
         LOG.error(this.lastErrorMessage);

      } catch (ResourceAccessException e) {
         this.lastErrorMessage = e.getCause() == null ? e.getMessage() : e.getCause().getMessage();
         LOG.error(this.lastErrorMessage);
      } catch (Exception e) {
         e.printStackTrace();
         this.lastErrorMessage = e.getMessage();
         LOG.error(this.lastErrorMessage);
      }
      return null;
   }
}
