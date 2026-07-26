package net.montoyo.wd.net;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;
import net.montoyo.wd.net.client_bound.*;
import net.montoyo.wd.net.server_bound.*;

import java.util.ArrayList;

public class WDNetworkRegistry {
	public static final String networkingVersion = "4";
	public static final SimpleChannel INSTANCE = NetworkRegistry.newSimpleChannel(
			new ResourceLocation("webdisplaystogether", "packetsystem"),
			() -> networkingVersion,
			(s) -> s.equals(networkingVersion),
			(s) -> s.equals(networkingVersion)
	);
	
	public static void sendToNearExcept() {
	
	}
	
	// if an old packet needs to be ported, refer to the following link
	// https://github.com/Mysticpasta1/webdisplaystogether/tree/ff55cbf1b27773c15f44f17ad3364da3a16b6ed9/src/main/java/net/montoyo/wd/net
	// however, I think I got all the essentials
	// only thing I skipped on, is the minepad
	static {
		ArrayList<NetworkEntry<?>> entries = new ArrayList<>();
		
		// login handshake
		entries.add(new NetworkEntry<>(S2CMessageServerInfo.class, S2CMessageServerInfo::new));
		entries.add(new NetworkEntry<>(C2SMessageMiniservConnect.class, C2SMessageMiniservConnect::new));
		entries.add(new NetworkEntry<>(S2CMessageMiniservKey.class, S2CMessageMiniservKey::new));
		
		// guis
		entries.add(new NetworkEntry<>(S2CMessageCloseGui.class, S2CMessageCloseGui::new));
		entries.add(new NetworkEntry<>(S2CMessageOpenGui.class, S2CMessageOpenGui::new));
		
		// screen creation
		entries.add(new NetworkEntry<>(S2CMessageAddScreen.class, S2CMessageAddScreen::new));
		
		// screen modifications
		entries.add(new NetworkEntry<>(C2SMessageScreenCtrl.class, C2SMessageScreenCtrl::new));
		entries.add(new NetworkEntry<>(S2CMessageScreenUpdate.class, S2CMessageScreenUpdate::new));
		
		// autocomplete
		entries.add(new NetworkEntry<>(C2SMessageACQuery.class, C2SMessageACQuery::new));
		entries.add(new NetworkEntry<>(S2CMessageACResult.class, S2CMessageACResult::new));
		
		// jsquery
		entries.add(new NetworkEntry<>(S2CMessageJSResponse.class, S2CMessageJSResponse::new));
		
		// minepad
		entries.add(new NetworkEntry<>(C2SMessageMinepadUrl.class, C2SMessageMinepadUrl::new));
		
		// server-side browser video streaming
		entries.add(new NetworkEntry<>(C2SMessageStreamCtrl.class, C2SMessageStreamCtrl::new));
		entries.add(new NetworkEntry<>(S2CMessageStreamFrame.class, S2CMessageStreamFrame::new));
		entries.add(new NetworkEntry<>(S2CMessageStreamAudio.class, S2CMessageStreamAudio::new));
		
		// speaker peripheral
		entries.add(new NetworkEntry<>(C2SMessageSpeakerPos.class, C2SMessageSpeakerPos::new));
		
		for (int i = 0; i < entries.size(); i++) entries.get(i).register(i, INSTANCE);
	}
	
	public static void init() {
		// nothing to do
	}
}
