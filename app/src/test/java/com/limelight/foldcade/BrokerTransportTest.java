package com.limelight.foldcade;

import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvHTTP;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class BrokerTransportTest {
    @Test public void serverInfoAndUnknownHttpsPortNeverNeedLocalCredentialsOrDirectHttp() throws Exception {
        List<String> paths = new ArrayList<>();
        NvHTTP http = new NvHTTP(new ComputerDetails.AddressTuple("stream.invalid", 47989),
                0, null, null, null, (path, query) -> {
                    paths.add(path);
                    assertNull(query); // No local uniqueid or random uuid is injected.
                    return "<root/>";
                });
        assertEquals("<root/>", http.getServerInfo(true));
        assertEquals("<root/>", http.getAppListRaw());
        assertEquals(List.of("serverinfo", "applist"), paths);
    }

    @Test public void revocationPropagatesWithoutDirectFallback() throws Exception {
        NvHTTP http = new NvHTTP(new ComputerDetails.AddressTuple("stream.invalid", 47989),
                0, null, null, null, (path, query) -> { throw new PluginProtocol.BrokerException("not_granted"); });
        assertEquals("not_granted", assertThrows(PluginProtocol.BrokerException.class,
                () -> http.getServerInfo(true)).code);
    }
}
