package io.emeraldpay.dshackle.upstream.arbitrum.json;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

@JsonDeserialize(using = NitroSyncingJsonDeserializer.class)
@JsonSerialize(using = NitroSyncingJsonSerializer.class)
public class NitroSyncingJson {

    private boolean syncing;

    private Long syncTargetMsgCount;
    private Long feedPendingMessageCount;
    private Long msgCount;

    public boolean isSyncing() {
        return syncing;
    }

    public void setSyncing(boolean syncing) {
        this.syncing = syncing;
    }

    public Long getSyncTargetMsgCount() {
        return syncTargetMsgCount;
    }

    public void setSyncTargetMsgCount(Long syncTargetMsgCount) {
        this.syncTargetMsgCount = syncTargetMsgCount;
    }

    public Long getFeedPendingMessageCount() {
        return feedPendingMessageCount;
    }

    public void setFeedPendingMessageCount(Long feedPendingMessageCount) {
        this.feedPendingMessageCount = feedPendingMessageCount;
    }

    public Long getMsgCount() {
        return msgCount;
    }

    public void setMsgCount(Long msgCount) {
        this.msgCount = msgCount;
    }
}
