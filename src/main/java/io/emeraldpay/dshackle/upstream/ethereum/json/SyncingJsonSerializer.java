/*
 * Copyright (c) 2016-2019 Igor Artamonov, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.emeraldpay.dshackle.upstream.ethereum.json;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;

public class SyncingJsonSerializer extends EtherJsonSerializer<SyncingJson> {

    @Override
    public void serialize(SyncingJson value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        if (value == null) {
            gen.writeNull();
        } else if (value.isSyncing()) {
            gen.writeStartObject();
            writeField(gen, "startingBlock", value.getStartingBlock());
            writeField(gen, "currentBlock", value.getCurrentBlock());
            writeField(gen, "highestBlock", value.getHighestBlock());
            gen.writeEndObject();
        } else {
            gen.writeBoolean(false);
        }
    }
}
