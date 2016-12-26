package lt.pavilonis.scan.cmm.client.ui.scanlog;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.ListView;
import lt.pavilonis.scan.cmm.client.App;
import lt.pavilonis.scan.cmm.client.representation.KeyRepresentation;
import lt.pavilonis.scan.cmm.client.representation.ScanLogRepresentation;
import lt.pavilonis.scan.cmm.client.representation.UserRepresentation;
import lt.pavilonis.scan.cmm.client.service.MessageSourceAdapter;
import lt.pavilonis.scan.cmm.client.service.WsRestClient;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.slf4j.LoggerFactory.getLogger;

@Component
public class ScanLogList extends ListView<ScanLogListElement> {
   private static final Logger LOG = getLogger(ScanLogList.class.getSimpleName());
   private static final int POSITION_FIRST = 0;
   private static final int QUEUE_LENGTH = 99;
   private final ObservableList<ScanLogListElement> container = FXCollections.observableArrayList();
   private ScanLogListElement lastSelection;

   @Autowired
   private WsRestClient wsClient;

   @Autowired
   private ScanLogKeyList scanLogKeyList;

   @Autowired
   private PhotoView photoView;

   @Autowired
   private MessageSourceAdapter messages;

   public ScanLogList() {
      setItems(container);
      setFocusTraversable(false);

      setOnMouseClicked(click -> Platform.runLater(this::processClick));
   }

   private void processClick() {
      ScanLogListElement newSelection = getSelectionModel().getSelectedItem();
      if (lastSelection == newSelection) {
         lastSelection.deactivate();
         lastSelection = null;
         getSelectionModel().clearSelection();
         scanLogKeyList.clear();
      } else {
         if (lastSelection != null) {
            lastSelection.deactivate();
         }
         lastSelection = newSelection;

         newSelection.activate();
         UserRepresentation user = newSelection.getUser();

         photoView.update(user.base16photo);
         scanLogKeyList.updateContainer(user.cardCode);
      }
   }

   public void addElement(ScanLogRepresentation representation) {
      Platform.runLater(() -> {
         if (container.size() > QUEUE_LENGTH) {
            container.remove(container.size() - 1);
         }

         ScanLogListElement element = new ScanLogListElement(representation, elementClickConsumer());
         container.add(POSITION_FIRST, element);

         if (lastSelection == null) {
            getSelectionModel().select(element);
            scanLogKeyList.updateContainer(element.getUser().cardCode);
            photoView.update(element.getUser().base16photo);
         }
      });
   }

   private BiConsumer<String, Integer> elementClickConsumer() {
      return (cardCode, keyNumber) -> {
         Consumer<Optional<KeyRepresentation>> wsResponseConsumer = response -> {
            if (response.isPresent()) {
               KeyRepresentation key = response.get();
               scanLogKeyList.append(key);
               LOG.info("Key {} assigned to cardCode {}", key.getKeyNumber(), key.getUser().cardCode);
            } else {
               App.displayWarning(messages.get(this, "canNotAssignKey"));
            }
         };
         wsClient.assignKey(cardCode, keyNumber, wsResponseConsumer);
      };
   }
}
