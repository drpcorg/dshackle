package io.emeraldpay.dshackle.upstream.arbitrum.json;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import io.emeraldpay.dshackle.upstream.ethereum.json.EtherJsonSerializer;

import java.io.IOException;

public class NitroSyncingJsonSerializer extends EtherJsonSerializer<NitroSyncingJson> {

    @Override
    public void serialize(NitroSyncingJson value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        if (value == null) {
            gen.writeNull();
        } else if (value.isSyncing()) {
            gen.writeStartObject();
            writeField(gen, "syncTargetMsgCount", value.getSyncTargetMsgCount());
            writeField(gen, "feedPendingMessageCount", value.getFeedPendingMessageCount());
            writeField(gen, "msgCount", value.getMsgCount());
            gen.writeEndObject();
        } else {
            gen.writeBoolean(false);
        }
    }
}
