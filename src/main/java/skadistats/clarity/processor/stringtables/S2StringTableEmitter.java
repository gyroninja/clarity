package skadistats.clarity.processor.stringtables;

import com.google.protobuf.ByteString;
import com.google.protobuf.ZeroCopy;
import org.xerial.snappy.Snappy;
import skadistats.clarity.decoder.BitStream;
import skadistats.clarity.event.Provides;
import skadistats.clarity.model.BuildNumberRange;
import skadistats.clarity.model.EngineType;
import skadistats.clarity.model.StringTable;
import skadistats.clarity.processor.reader.OnMessage;
import skadistats.clarity.processor.runner.Context;
import skadistats.clarity.util.LZSS;
import skadistats.clarity.wire.s2.proto.S2NetMessages;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

@Provides(value = {OnStringTableCreated.class, OnStringTableEntry.class}, engine = EngineType.SOURCE2)
@StringTableEmitter
public class S2StringTableEmitter extends BaseStringTableEmitter {

    private interface UncompressorFunc {
        byte[] uncompress(byte[] src, int size) throws IOException;
    }

    private static final Map<BuildNumberRange, UncompressorFunc> UNCOMPRESSORS = new HashMap<>();
    static {
        UNCOMPRESSORS.put(new BuildNumberRange(null, 962), new UncompressorFunc() {
            @Override
            public byte[] uncompress(byte[] src, int size) throws IOException {
                byte[] dst = new byte[size];
                LZSS.unpack(src, dst);
                return dst;
            }
        });
        UNCOMPRESSORS.put(new BuildNumberRange(963, null), new UncompressorFunc() {
            @Override
            public byte[] uncompress(byte[] src, int size) throws IOException {
                return Snappy.uncompress(src);
            }
        });
    }

    @OnMessage(S2NetMessages.CSVCMsg_CreateStringTable.class)
    public void onCreateStringTable(Context ctx, S2NetMessages.CSVCMsg_CreateStringTable message) throws IOException {
        if (isProcessed(message.getName())) {
            StringTable table = new StringTable(
                message.getName(),
                100,
                message.getUserDataFixedSize(),
                message.getUserDataSize(),
                message.getUserDataSizeBits(),
                message.getFlags()
            );

            ByteString data = message.getStringData();
            if (message.getDataCompressed()) {
                byte[] src = ZeroCopy.extract(data);
                for (Map.Entry<BuildNumberRange, UncompressorFunc> funcEntry : UNCOMPRESSORS.entrySet()) {
                    if (funcEntry.getKey().appliesTo(ctx.getBuildNumber())) {
                        src = funcEntry.getValue().uncompress(src, message.getUncompressedSize());
                    }
                }
                data = ZeroCopy.wrap(src);
            }
            decodeEntries(ctx, table, 3, data, message.getNumEntries());
            ctx.createEvent(OnStringTableCreated.class, int.class, StringTable.class).raise(numTables, table);
        }
        numTables++;
    }

    @OnMessage(S2NetMessages.CSVCMsg_UpdateStringTable.class)
    public void onUpdateStringTable(Context ctx, S2NetMessages.CSVCMsg_UpdateStringTable message) {
        StringTables stringTables = ctx.getProcessor(StringTables.class);
        StringTable table = stringTables.forId(message.getTableId());
        if (table != null) {
            decodeEntries(ctx, table, 2, message.getStringData(), message.getNumChangedEntries());
        }
    }

    private void decodeEntries(Context ctx, StringTable table, int mode, ByteString data, int numEntries) {
        BitStream stream = new BitStream(data);
        LinkedList<String> keyHistory = new LinkedList<>();

        int index = -1;
        StringBuilder nameBuf = new StringBuilder();
        while (numEntries-- > 0) {
            // read index
            if (stream.readBitFlag()) {
                index++;
            } else {
                index = stream.readVarUInt() + 1;
            }
            // read name
            nameBuf.setLength(0);
            if (stream.readBitFlag()) {
                if (stream.readBitFlag()) {
                    int basis = stream.readUBitInt(5);
                    int length = stream.readUBitInt(5);
                    nameBuf.append(keyHistory.get(basis).substring(0, length));
                    nameBuf.append(stream.readString(MAX_NAME_LENGTH - length));
                } else {
                    nameBuf.append(stream.readString(MAX_NAME_LENGTH));
                }
                if (keyHistory.size() == KEY_HISTORY_SIZE) {
                    keyHistory.remove(0);
                }
                keyHistory.add(nameBuf.toString());
            }
            // read value
            ByteString value = null;
            if (stream.readBitFlag()) {
                int bitLength;
                if (table.getUserDataFixedSize()) {
                    bitLength = table.getUserDataSizeBits();
                } else {
                    if ((table.getFlags() & 0x1) != 0) {
                        // this is the case for the instancebaseline for console recorded replays
                        stream.skip(1);
                    }
                    bitLength = stream.readUBitInt(17) * 8;
                }
                value = ByteString.copyFrom(stream.readBitsAsByteArray(bitLength));
            }
            setSingleEntry(ctx, table, mode, index, nameBuf.toString(), value);
        }
    }

}
