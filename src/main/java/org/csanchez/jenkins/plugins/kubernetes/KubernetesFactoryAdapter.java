package org.csanchez.jenkins.plugins.kubernetes;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardCertificateCredentials;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.UsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import hudson.security.ACL;
import hudson.util.Secret;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import jenkins.model.Jenkins;
import org.apache.commons.codec.binary.Base64;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import javax.annotation.CheckForNull;

/**
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
public class KubernetesFactoryAdapter {

    private static final int DEFAULT_CONNECT_TIMEOUT = 5;
    private static final int DEFAULT_READ_TIMEOUT = 15;

    private final String serviceAddress;
    private final String namespace;
    @CheckForNull
    private final String caCertData;
    @CheckForNull
    private final StandardCredentials credentials;

    private final boolean skipTlsVerify;
    private final int connectTimeout;
    private final int readTimeout;

    public KubernetesFactoryAdapter(String serviceAddress, @CheckForNull String caCertData,
                                    @CheckForNull String credentials, boolean skipTlsVerify) {
        this(serviceAddress, null, caCertData, credentials, skipTlsVerify);
    }

    public KubernetesFactoryAdapter(String serviceAddress, String namespace, @CheckForNull String caCertData,
                                    @CheckForNull String credentials, boolean skipTlsVerify) {
        this(serviceAddress, namespace, caCertData, credentials, skipTlsVerify, DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_TIMEOUT);
    }

    public KubernetesFactoryAdapter(String serviceAddress, String namespace, @CheckForNull String caCertData,
                                    @CheckForNull String credentials, boolean skipTlsVerify, int connectTimeout, int readTimeout) {
        this.serviceAddress = serviceAddress;
        this.namespace = namespace;
        this.caCertData = caCertData;
        this.credentials = credentials != null ? getCredentials(credentials) : null;
        this.skipTlsVerify = skipTlsVerify;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
    }

    private StandardCredentials getCredentials(String credentials) {
        return CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentials(StandardCredentials.class,
                        Jenkins.getInstance(), ACL.SYSTEM, Collections.<DomainRequirement>emptyList()),
                CredentialsMatchers.withId(credentials)
        );
    }

    public KubernetesClient createClient() throws NoSuchAlgorithmException, UnrecoverableKeyException,
            KeyStoreException, IOException, CertificateEncodingException {
        ConfigBuilder builder = new ConfigBuilder().withMasterUrl(serviceAddress)
                .withRequestTimeout(readTimeout * 1000)
                .withConnectionTimeout(connectTimeout * 1000);

        if (namespace != null && !namespace.isEmpty()) {
            builder.withNamespace(namespace);
        }
        if (credentials != null) {
            if (credentials instanceof TokenProducer) {
                final String token = ((TokenProducer)credentials).getToken(serviceAddress, caCertData, skipTlsVerify);
                builder.withOauthToken(token);
            }
            else if (credentials instanceof UsernamePasswordCredentials) {
                UsernamePasswordCredentials usernamePassword = (UsernamePasswordCredentials) credentials;
                builder.withUsername(usernamePassword.getUsername()).withPassword(Secret.toString(usernamePassword.getPassword()));
            }
            else if (credentials instanceof StandardCertificateCredentials) {
                StandardCertificateCredentials certificateCredentials = (StandardCertificateCredentials) credentials;
                KeyStore keyStore = certificateCredentials.getKeyStore();
                String alias = keyStore.aliases().nextElement();
                X509Certificate certificate = (X509Certificate) keyStore.getCertificate(alias);
                Key key = keyStore.getKey(alias, Secret.toString(certificateCredentials.getPassword()).toCharArray());
                builder.withClientCertData(Base64.encodeBase64String(certificate.getEncoded()))
                        .withClientKeyData(pemEncodeKey(key))
                        .withClientKeyPassphrase(Secret.toString(certificateCredentials.getPassword()));
            }
        }

        if (skipTlsVerify) {
            builder.withTrustCerts(true);
        }

        if (caCertData != null) {
            builder.withCaCertData(caCertData);
        }
        return new DefaultKubernetesClient(builder.build());
    }

    private static String pemEncodeKey(Key key) {
        return Base64.encodeBase64String(new StringBuilder() //
                .append("-----BEGIN PRIVATE KEY-----\n") //
                .append(Base64.encodeBase64String(key.getEncoded())) //
                .append("\n-----END PRIVATE KEY-----\n") //
                .toString().getBytes(StandardCharsets.UTF_8));
    }
}
