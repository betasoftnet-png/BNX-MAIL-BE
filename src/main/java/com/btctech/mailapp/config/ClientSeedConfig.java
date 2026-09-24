package com.btctech.mailapp.config;

import com.btctech.mailapp.entity.ClientApp;
import com.btctech.mailapp.repository.ClientAppRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class ClientSeedConfig {

    private final ClientAppRepository clientAppRepository;

    @Bean
    public CommandLineRunner seedClients() {
        return args -> {
            clientAppRepository.findByClientId("kinsword").ifPresentOrElse(
                client -> {
                    log.info("Updating test OAuth client redirect URI...");
                    client.setRedirectUri("https://www.kinsword.com");
                    clientAppRepository.save(client);
                },
                () -> {
                    log.info("Seeding test OAuth client...");
                    ClientApp client = ClientApp.builder()
                            .clientId("kinsword")
                            .clientSecret("secure-test-secret-2026")
                            .appName("KINSWORD")
                            .redirectUri("https://www.kinsword.com")
                            .build();
                    clientAppRepository.save(client);
                    log.info("Test OAuth client seeded: kinsword");
                }
            );
            
            clientAppRepository.findByClientId("cliks-app").ifPresentOrElse(
                client -> {
                    log.info("Updating cliks-app OAuth client redirect URI...");
                    client.setRedirectUri("https://cliks.beta-softnet.com/auth");
                    clientAppRepository.save(client);
                },
                () -> {
                    log.info("Seeding cliks-app OAuth client...");
                    ClientApp client = ClientApp.builder()
                            .clientId("cliks-app")
                            .clientSecret("secure-cliks-secret-2026")
                            .appName("Cliks")
                            .redirectUri("https://cliks.beta-softnet.com/auth")
                            .build();
                    clientAppRepository.save(client);
                    log.info("Test OAuth client seeded: cliks-app");
                }
            );

            clientAppRepository.findByClientId("cliks-business").ifPresentOrElse(
                client -> {
                    log.info("Updating cliks-business OAuth client redirect URI...");
                    client.setRedirectUri("https://cliksbusiness.com/");
                    clientAppRepository.save(client);
                },
                () -> {
                    log.info("Seeding cliks-business OAuth client...");
                    ClientApp client = ClientApp.builder()
                            .clientId("cliks-business")
                            .clientSecret("secure-cliks-biz-secret-2026")
                            .appName("Cliks Business")
                            .redirectUri("https://cliksbusiness.com/")
                            .build();
                    clientAppRepository.save(client);
                    log.info("Test OAuth client seeded: cliks-business");
                }
            );

            clientAppRepository.findByClientId("beta_website").ifPresentOrElse(
                client -> {
                    log.info("Updating beta_website OAuth client redirect URI...");
                    client.setRedirectUri("https://www.beta-softnet.com/,https://beta-softnet.com/,https://www.beta-softnet.com,https://beta-softnet.com,http://localhost:5173,http://localhost:5173/");
                    clientAppRepository.save(client);
                },
                () -> {
                    log.info("Seeding beta_website OAuth client...");
                    ClientApp client = ClientApp.builder()
                            .clientId("beta_website")
                            .clientSecret("secure-beta-secret-2026")
                            .appName("Beta Website")
                            .redirectUri("https://www.beta-softnet.com/,https://beta-softnet.com/,https://www.beta-softnet.com,https://beta-softnet.com,http://localhost:5173,http://localhost:5173/")
                            .build();
                    clientAppRepository.save(client);
                    log.info("Test OAuth client seeded: beta_website");
                }
            );

            clientAppRepository.findByClientId("bit-tool").ifPresentOrElse(
                client -> {
                    log.info("Updating bit-tool OAuth client redirect URI...");
                    client.setRedirectUri("https://www.bit-tool.com/auth,https://bit-tool.com/auth,http://localhost:5173/auth,http://localhost:5173/auth/");
                    clientAppRepository.save(client);
                },
                () -> {
                    log.info("Seeding bit-tool OAuth client...");
                    ClientApp client = ClientApp.builder()
                            .clientId("bit-tool")
                            .clientSecret("secure-bit-tool-secret-2026")
                            .appName("Bit Tool")
                            .redirectUri("https://www.bit-tool.com/auth,https://bit-tool.com/auth,http://localhost:5173/auth,http://localhost:5173/auth/")
                            .build();
                    clientAppRepository.save(client);
                    log.info("Test OAuth client seeded: bit-tool");
                }
            );

            clientAppRepository.findByClientId("account-ui").ifPresentOrElse(
                client -> {
                    log.info("Updating account-ui OAuth client redirect URI...");
                    client.setRedirectUri("https://account.beta-softnet.com,https://www.account.beta-softnet.com,http://localhost:5173,http://localhost:5173/,http://localhost:3000,http://localhost:3000/,http://localhost:3001,http://localhost:3001/");
                    clientAppRepository.save(client);
                },
                () -> {
                    log.info("Seeding account-ui OAuth client...");
                    ClientApp client = ClientApp.builder()
                            .clientId("account-ui")
                            .clientSecret("secure-account-secret-2026")
                            .appName("Account Settings")
                            .redirectUri("https://account.beta-softnet.com,https://www.account.beta-softnet.com,http://localhost:5173,http://localhost:5173/,http://localhost:3000,http://localhost:3000/,http://localhost:3001,http://localhost:3001/")
                            .build();
                    clientAppRepository.save(client);
                    log.info("Test OAuth client seeded: account-ui");
                }
            );

            clientAppRepository.findByClientId("beta-storage").ifPresentOrElse(
                client -> {
                    log.info("Updating beta-storage OAuth client redirect URI...");
                    client.setRedirectUri("https://storage.beta-softnet.com,https://www.storage.beta-softnet.com,https://storage.beta-softnet.com/,http://localhost:5173,http://localhost:5173/,http://localhost:3000,http://localhost:3000/");
                    clientAppRepository.save(client);
                },
                () -> {
                    log.info("Seeding beta-storage OAuth client...");
                    ClientApp client = ClientApp.builder()
                            .clientId("beta-storage")
                            .clientSecret("secure-storage-secret-2026")
                            .appName("Beta Storage")
                            .redirectUri("https://storage.beta-softnet.com,https://www.storage.beta-softnet.com,https://storage.beta-softnet.com/,http://localhost:5173,http://localhost:5173/,http://localhost:3000,http://localhost:3000/")
                            .build();
                    clientAppRepository.save(client);
                    log.info("Test OAuth client seeded: beta-storage");
                }
            );
        };
    }
}
