package io.emeraldpay.dshackle.upstream.arbitrum.json;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import io.emeraldpay.dshackle.upstream.arbitrum.json.NitroSyncingJson;
import io.emeraldpay.dshackle.upstream.ethereum.json.EtherJsonDeserializer;

import java.io.IOException;

public class NitroSyncingJsonDeserializer extends EtherJsonDeserializer<NitroSyncingJson> {

    @Override
    public NitroSyncingJson deserialize(JsonParser jp, DeserializationContext ctxt) throws IOException, JsonProcessingException {
        JsonNode node = jp.readValueAsTree();
        NitroSyncingJson resp = new NitroSyncingJson();
        if (node.isBoolean()) {
            resp.setSyncing(node.asBoolean());
        } else {
            resp.setSyncing(true);
            resp.setSyncTargetMsgCount(getLong(node, "syncTargetMsgCount"));
            resp.setFeedPendingMessageCount(getLong(node, "feedPendingMessageCount"));
            resp.setMsgCount(getLong(node, "msgCount"));
        }
        return resp;
    }
}
