package io.digdag.standards.operator.td;

import com.treasuredata.client.AbstractTDClientBuilder;
import com.treasuredata.client.TDClient;
import io.digdag.client.config.Config;
import io.digdag.client.config.ConfigException;

class TDClientFactory
{
    static TDClient clientFromConfig(Config params)
    {
        return clientFromConfig(params, false);
    }

    static TDClient clientFromConfig(Config params, boolean retries)
    {

        String apikey = params.get("apikey", String.class).trim();
        if (apikey.isEmpty()) {
            throw new ConfigException("Parameter 'apikey' is empty");
        }

        AbstractTDClientBuilder<TDClient> builder = TDClient.newBuilder(false)
                .setEndpoint(params.get("endpoint", String.class, "api.treasuredata.com"))
                .setUseSSL(params.get("use_ssl", boolean.class, true))
                .setApiKey(apikey);

        if (!retries) {
            // disable td-client's retry mechanism
            builder.setRetryLimit(0);
        }

        return builder.build();
    }
}
