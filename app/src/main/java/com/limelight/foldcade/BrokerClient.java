package com.limelight.foldcade;

import android.content.Context;
import android.content.pm.ProviderInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;

import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.http.PairingManager;

import java.io.IOException;
import java.util.ArrayList;

/** No client key, certificate, or pairing state ever crosses into this process. */
public final class BrokerClient implements NvHTTP.RequestTransport {
    private final Context context;
    private final String authority, hostUuid;
    private final String hostPackage;

    public BrokerClient(Context context, String authority, String hostUuid) throws IOException {
        this.context = context.getApplicationContext();
        this.authority = authority;
        this.hostUuid = hostUuid;
        this.hostPackage = trustedPackage();
    }

    private String trustedPackage() throws IOException {
        ProviderInfo provider = context.getPackageManager().resolveContentProvider(authority, 0);
        if (provider == null || !provider.exported ||
                !("app.foldcade".equals(provider.packageName) || "app.foldcade.debug".equals(provider.packageName)) ||
                !authority.equals(provider.packageName + ".plugins")) {
            throw new IOException("The play request does not point to a trusted Foldcade broker.");
        }
        return provider.packageName;
    }

    @SuppressWarnings("deprecation")
    private Bundle call(String method, String arg, Bundle extras) throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("Broker calls require a worker thread");
        try {
            trustedPackage();
            Bundle reply = context.getContentResolver().call(Uri.parse("content://" + authority), method, arg, extras);
            if (reply == null) throw new PluginProtocol.BrokerException("internal");
            PluginProtocol.requireSuccess(reply.get("ok"), reply.get("error"));
            return reply;
        } catch (PluginProtocol.BrokerException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new PluginProtocol.BrokerException("internal");
        }
    }

    public void hello() throws IOException {
        Bundle reply = call("hello", null, null);
        if (reply.getInt("protocol", 0) < 1 || !hostPackage.equals(reply.getString("hostPackage")) ||
                reply.getStringArray("granted") == null) {
            throw new PluginProtocol.BrokerException("internal");
        }
    }

    public ComputerDetails host() throws IOException {
        Bundle reply = call("gamestream.hosts", null, null);
        ArrayList<Bundle> hosts = reply.getParcelableArrayList("hosts");
        if (hosts == null) throw new PluginProtocol.BrokerException("internal");
        for (Bundle host : hosts) {
            if (host == null || !hostUuid.equals(host.getString("uuid"))) continue;
            String name = host.getString("name"), address = host.getString("address");
            int httpPort = host.getInt("httpPort", 0), httpsPort = host.getInt("httpsPort", -1);
            if (name == null || name.isEmpty() || address == null || address.isEmpty() ||
                    httpPort <= 0 || httpPort > 65535 || httpsPort < 0 || httpsPort > 65535) {
                throw new PluginProtocol.BrokerException("internal");
            }
            ComputerDetails details = new ComputerDetails();
            details.uuid = hostUuid;
            details.name = name;
            details.activeAddress = new ComputerDetails.AddressTuple(address, httpPort);
            details.httpsPort = httpsPort;
            details.macAddress = host.getString("mac");
            details.state = ComputerDetails.State.ONLINE;
            details.pairState = PairingManager.PairState.PAIRED;
            return details;
        }
        throw new PluginProtocol.BrokerException("unknown_host");
    }

    @Override
    @SuppressWarnings("deprecation")
    public String request(String path, String query) throws IOException {
        PluginProtocol.validateQuery(path, query);
        Bundle args = new Bundle();
        args.putString("path", path);
        if (query != null) args.putString("query", query);
        Bundle reply = call("gamestream.request", hostUuid, args);
        return PluginProtocol.responseBody(reply.get("status"), reply.get("body"));
    }
}
