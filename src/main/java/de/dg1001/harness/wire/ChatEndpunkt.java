package de.dg1001.harness.wire;

import de.dg1001.harness.wire.Messages.ChatResponse;
import de.dg1001.harness.wire.Messages.Message;
import de.dg1001.harness.wire.Messages.ToolSpec;

import java.util.List;

/**
 * Ein Ding, das eine Anfrage beantwortet.
 *
 * <p>Existiert nur, damit {@link Retry} sich vor {@link ChatClient} schieben
 * kann, ohne dass der Agent den Unterschied kennt. Genau dafuer sind Huellen
 * da — und genau deshalb ist die Wiederholung nicht im Client selbst.
 */
public interface ChatEndpunkt {
    ChatResponse complete(List<Message> verlauf, List<ToolSpec> werkzeuge);
}
