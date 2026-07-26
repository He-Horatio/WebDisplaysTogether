/*
 * Copyright (C) 2018 BARBOTIN Nicolas
 */

package net.montoyo.wd.net.client_bound;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import net.montoyo.wd.WebDisplays;
import net.montoyo.wd.core.JSServerRequest;
import net.montoyo.wd.net.Packet;
import net.montoyo.wd.utilities.Log;

public class S2CMessageJSResponse extends Packet {

    private int id;
    private JSServerRequest type;
    private boolean success;
    private byte[] data;
    private int errCode;
    private String errString;

    public S2CMessageJSResponse(int id, JSServerRequest t, byte[] d) {
        this.id = id;
        type = t;
        success = true;
        data = d;
    }

    public S2CMessageJSResponse(int id, JSServerRequest t, int code, String err) {
        this.id = id;
        type = t;
        success = false;
        errCode = code;
        errString = err;
    }
    
    public S2CMessageJSResponse(FriendlyByteBuf buf) {
        super(buf);
        
        int id = buf.readInt();
        JSServerRequest type = JSServerRequest.fromID(buf.readByte());
        boolean success = buf.readBoolean();

        byte[] data = null;

        int errCode;
        String errString;

        if(success) {
            data = new byte[buf.readByte()];
            buf.readBytes(data);

            this.id = id;
            this.type = type;
            this.data = data;
        } else {
            errCode = buf.readInt();
            errString = buf.readUtf();
            this.id = id;
            this.type = type;
            this.errCode = errCode;
            this.errString = errString;
        }
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeInt(id);
        buf.writeByte(type.ordinal());
        buf.writeBoolean(success);

        if(success) {
            buf.writeByte(data.length);
            buf.writeBytes(data); //TODO: Eventually compress this data
        } else {
            buf.writeInt(errCode);
            buf.writeUtf(errString);
        }
    }

    public void handle(NetworkEvent.Context ctx) {
        if (checkClient(ctx)) {
            ctx.enqueueWork(() -> {
                try {
                    if (success)
                        WebDisplays.PROXY.handleJSResponseSuccess(id, type, data);
                    else
                        WebDisplays.PROXY.handleJSResponseError(id, type, errCode, errString);
                } catch (Throwable t) {
                    Log.warningEx("Could not handle JS response", t);
                }
            });
            ctx.setPacketHandled(true);
        }
    }
}
