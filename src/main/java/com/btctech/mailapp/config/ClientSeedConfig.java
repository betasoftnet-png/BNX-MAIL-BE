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
                    client.setRedirectUri("https://www.kinsword.com,https://kinsword.com,https://www.kinsword.com/,https://kinsword.com/,https://www.kinsword.com/auth,https://kinsword.com/auth,http://www.kinsword.com,http://kinsword.com,http://localhost:3000,http://localhost:5173");
                    clientAppRepository.save(client);
                },
                () -> {
                    log.info("Seeding test OAuth client...");
                    ClientApp client = ClientApp.builder()
                            .clientId("kinsword")
                            .clientSecret("secure-test-secret-2026")
                            .appName("KINSWORD")
                            .redirectUri("https://www.kinsword.com,https://kinsword.com,https://www.kinsword.com/,https://kinsword.com/,https://www.kinsword.com/auth,https://kinsword.com/auth,http://www.kinsword.com,http://kinsword.com,http://localhost:3000,http://localhost:5173")
                            .build();
                    clientAppRepository.save(client);
                    log.info("Test OAuth client seeded: kinsword");
                }
            );
            
            clientAppRepository.findByClientId("cliks-app").ifPresentOrElse(
                client -> {
                    log.info("Updating cliks-app OAuth client redirect URI...");
                    client.setRedirectUri("https://cliks.beta-softnet.com/auth,https://cliks.beta-softnet.com,https://cliks.beta-softnet.com/,https://www.cliks.beta-softnet.com/auth,https://www.cliks.beta-softnet.com,https://www.cliks.beta-softnet.com/,http://cliks.beta-softnet.com/auth,http://cliks.beta-softnet.com,http://cliks.beta-softnet.com/,http://www.cliks.beta-softnet.com/auth,http://www.cliks.beta-softnet.com,http://www.cliks.beta-softnet.com/,https://1qjzhcsfbpwndetugbkwufvf.151.185.41.212.sslip.io,https://1qjzhcsfbpwndetugbkwufvf.151.185.41.212.sslip.io/,https://1qjzhcsfbpwndetugbkwufvf.151.185.41.212.sslip.io/auth,https://1qjzhcsfbpwndetugbkwufvf.151.185.41.212.sslip.io/auth/,http://1qjzhcsfbpwndetugbkwufvf.151.185.41.212.sslip.io,http://1qjzhcsfbpwndetugbkwufvf.151.185.41.212.sslip.io/,http://1qjzhcsfbpwndetugbkwufvf.151.185.41.212.sslip.io/auth,http://1qjzhcsfbpwndetugbkwufvf.151.185.41.212.sslip.io/auth/,https://znqi2vpr3hftok6dskqe1xn6.151.185.41.212.sslip.io,https://znqi2vpr3hftok6dskqe1xn6.151.185.41.212.sslip.io/,https://znqi2vpr3hftok6dskqe1xn6.151.185.41.212.sslip.io/auth,https://znqi2vpr3hftok6dskqe1xn6.151.185.41.212.sslip.io/auth/,http://znqi2vpr3hftok6dskqe1xn6.151.185.41.212.sslip.io,http://znqi2vpr3hftok6dskqe1xn6.151.185.41.212.sslip.io/,http://znqi2vpr3hftok6dskqe1xn6.151.185.41.212.sslip.io/auth,http://znqi2vpr3hftok6dskqe1xn6.151.185.41.212.sslip.io/auth/,http://localhost:5173/auth,http://localhost:5173,http://localhost:3000/auth,http://localhost:3000");
                    clientAppRepository.save(client);
                },
                () -> {
                    log.info("Seeding cliks-app OAuth client...");
                    ClientApp client = ClientApp.builder()
                            .clientId("cliks-app")
                            .clientSecret("secure-cliks-secret-2026")
                            .appName("Cliks")
                            .redirectUri("https://cliks.beta-softnet.com/auth,https://cliks.beta-softnet.com,https://cliks.beta-softnet.com/,https://www.cliks.beta-softnet.com/auth,https://www.cliks.beta-softnet.com,https://www.cliks.beta-softnet.com/,http://cliks.beta-softnet.com/auth,http://cliks.beta-softnet.com,http://cliks.beta-softnet.com/,http://www.cliks.beta-softnet.com/auth,http://www.cliks.beta-softnet.com,http://www.cliks.beta-softnet.com/,https://1qjzhcsfbpwndetugbkwufvf.151.185.41.212.sslip.io,https://1qjzhcsfbpwndetugbkwufvf.151.185.41.212.sslip.io/,https://1qjzhcsfbpwndetugbkwufvf.151.185.41.212.sslip.io/auth,https://1qjzhcsfbpwndetugbkwufvf.151.185.41.212.sslip.io/auth/,http://1qjzhcsfbpwndetugbkwufvf.151.185.41.212.sslip.io,http://1qjzhcsfbpwndetugbkwufvf.151.185.41.212.sslip.io/,http://1qjzhcsfbpwndetugbkwufvf.151.185.41.212.sslip.io/auth,http://1qjzhcsfbpwndetugbkwufvf.151.185.41.212.sslip.io/auth/,https://znqi2vpr3hftok6dskqe1xn6.151.185.41.212.sslip.io,https://znqi2vpr3hftok6dskqe1xn6.151.185.41.212.sslip.io/,https://znqi2vpr3hftok6dskqe1xn6.151.185.41.212.sslip.io/auth,https://znqi2vpr3hftok6dskqe1xn6.151.185.41.212.sslip.io/auth/,http://znqi2vpr3hftok6dskqe1xn6.151.185.41.212.sslip.io,http://znqi2vpr3hftok6dskqe1xn6.151.185.41.212.sslip.io/,http://znqi2vpr3hftok6dskqe1xn6.151.185.41.212.sslip.io/auth,http://znqi2vpr3hftok6dskqe1xn6.151.185.41.212.sslip.io/auth/,http://localhost:5173/auth,http://localhost:5173,http://localhost:3000/auth,http://localhost:3000")
                            .build();
                    clientAppRepository.save(client);
                    log.info("Test OAuth client seeded: cliks-app");
                }
            );

            clientAppRepository.findByClientId("cliks-business").ifPresentOrElse(
                client -> {
                    log.info("Updating cliks-business OAuth client redirect URI...");
                    client.setRedirectUri("https://cliksbusiness.com/,https://cliksbusiness.com,https://www.cliksbusiness.com/,https://www.cliksbusiness.com,https://cliksbusiness.com/auth,https://www.cliksbusiness.com/auth,http://cliksbusiness.com/,http://cliksbusiness.com,https://07b0lcf6hdobzm2tucd31oh8.151.185.41.212.sslip.io/,https://07b0lcf6hdobzm2tucd31oh8.151.185.41.212.sslip.io,https://07b0lcf6hdobzm2tucd31oh8.151.185.41.212.sslip.io/auth,https://07b0lcf6hdobzm2tucd31oh8.151.185.41.212.sslip.io/auth/,http://07b0lcf6hdobzm2tucd31oh8.151.185.41.212.sslip.io/,http://07b0lcf6hdobzm2tucd31oh8.151.185.41.212.sslip.io,http://07b0lcf6hdobzm2tucd31oh8.151.185.41.212.sslip.io/auth,https://z7ibjggnrtzynrguut8nerqr.151.185.41.212.sslip.io,https://z7ibjggnrtzynrguut8nerqr.151.185.41.212.sslip.io/,https://z7ibjggnrtzynrguut8nerqr.151.185.41.212.sslip.io/auth,http://localhost:3000,http://localhost:5173");
                    clientAppRepository.save(client);
                },
                () -> {
                    log.info("Seeding cliks-business OAuth client...");
                    ClientApp client = ClientApp.builder()
                            .clientId("cliks-business")
                            .clientSecret("secure-cliks-biz-secret-2026")
                            .appName("Cliks Business")
                            .redirectUri("https://cliksbusiness.com/,https://cliksbusiness.com,https://www.cliksbusiness.com/,https://www.cliksbusiness.com,https://cliksbusiness.com/auth,https://www.cliksbusiness.com/auth,http://cliksbusiness.com/,http://cliksbusiness.com,https://07b0lcf6hdobzm2tucd31oh8.151.185.41.212.sslip.io/,https://07b0lcf6hdobzm2tucd31oh8.151.185.41.212.sslip.io,https://07b0lcf6hdobzm2tucd31oh8.151.185.41.212.sslip.io/auth,https://07b0lcf6hdobzm2tucd31oh8.151.185.41.212.sslip.io/auth/,http://07b0lcf6hdobzm2tucd31oh8.151.185.41.212.sslip.io/,http://07b0lcf6hdobzm2tucd31oh8.151.185.41.212.sslip.io,http://07b0lcf6hdobzm2tucd31oh8.151.185.41.212.sslip.io/auth,https://z7ibjggnrtzynrguut8nerqr.151.185.41.212.sslip.io,https://z7ibjggnrtzynrguut8nerqr.151.185.41.212.sslip.io/,https://z7ibjggnrtzynrguut8nerqr.151.185.41.212.sslip.io/auth,http://localhost:3000,http://localhost:5173")
                            .build();
                    clientAppRepository.save(client);
                    log.info("Test OAuth client seeded: cliks-business");
                }
            );

            clientAppRepository.findByClientId("beta_website").ifPresentOrElse(
                client -> {
                    log.info("Updating beta_website OAuth client redirect URI...");
                    client.setRedirectUri("https://www.beta-softnet.com/,https://beta-softnet.com/,https://www.beta-softnet.com,https://beta-softnet.com,https://z7ibjggnrtzynrguut8nerqr.151.185.41.212.sslip.io,https://z7ibjggnrtzynrguut8nerqr.151.185.41.212.sslip.io/,https://z7ibjggnrtzynrguut8nerqr.151.185.41.212.sslip.io/auth,https://z7ibjggnrtzynrguut8nerqr.151.185.41.212.sslip.io/auth/,http://localhost:5173,http://localhost:5173/,http://localhost:3000,http://localhost:3000/");
                    clientAppRepository.save(client);
                },
                () -> {
                    log.info("Seeding beta_website OAuth client...");
                    ClientApp client = ClientApp.builder()
                            .clientId("beta_website")
                            .clientSecret("secure-beta-secret-2026")
                            .appName("Beta Website")
                            .redirectUri("https://www.beta-softnet.com/,https://beta-softnet.com/,https://www.beta-softnet.com,https://beta-softnet.com,https://z7ibjggnrtzynrguut8nerqr.151.185.41.212.sslip.io,https://z7ibjggnrtzynrguut8nerqr.151.185.41.212.sslip.io/,https://z7ibjggnrtzynrguut8nerqr.151.185.41.212.sslip.io/auth,https://z7ibjggnrtzynrguut8nerqr.151.185.41.212.sslip.io/auth/,http://localhost:5173,http://localhost:5173/,http://localhost:3000,http://localhost:3000/")
                            .build();
                    clientAppRepository.save(client);
                    log.info("Test OAuth client seeded: beta_website");
                }
            );

            clientAppRepository.findByClientId("bit-tool").ifPresentOrElse(
                client -> {
                    log.info("Updating bit-tool OAuth client redirect URI...");
                    client.setRedirectUri("https://www.bit-tool.com/auth,https://bit-tool.com/auth,https://www.bit-tool.com,https://bit-tool.com,https://app2.bit-tool.com,https://app2.bit-tool.com/,https://app2.bit-tool.com/auth,https://app2.bit-tool.com/auth/,http://app2.bit-tool.com,http://app2.bit-tool.com/,http://app2.bit-tool.com/auth,http://app2.bit-tool.com/auth/,https://z7ibjggnrtzynrguut8nerqr.151.185.41.212.sslip.io,https://z7ibjggnrtzynrguut8nerqr.151.185.41.212.sslip.io/,https://z7ibjggnrtzynrguut8nerqr.151.185.41.212.sslip.io/auth,https://z7ibjggnrtzynrguut8nerqr.151.185.41.212.sslip.io/auth/,http://z7ibjggnrtzynrguut8nerqr.151.185.41.212.sslip.io,http://z7ibjggnrtzynrguut8nerqr.151.185.41.212.sslip.io/,http://z7ibjggnrtzynrguut8nerqr.151.185.41.212.sslip.io/auth,http://z7ibjggnrtzynrguut8nerqr.151.185.41.212.sslip.io/auth/,https://z7m99lkvh7u6ncfsumfwcrdl.151.185.41.212.sslip.io,https://z7m99lkvh7u6ncfsumfwcrdl.151.185.41.212.sslip.io/,https://z7m99lkvh7u6ncfsumfwcrdl.151.185.41.212.sslip.io/auth,https://z7m99lkvh7u6ncfsumfwcrdl.151.185.41.212.sslip.io/auth/,http://z7m99lkvh7u6ncfsumfwcrdl.151.185.41.212.sslip.io,http://z7m99lkvh7u6ncfsumfwcrdl.151.185.41.212.sslip.io/,http://z7m99lkvh7u6ncfsumfwcrdl.151.185.41.212.sslip.io/auth,http://z7m99lkvh7u6ncfsumfwcrdl.151.185.41.212.sslip.io/auth/,https://z8m99lkvh7u6ncfsumfwcrdl.151.185.41.212.sslip.io,https://z8m99lkvh7u6ncfsumfwcrdl.151.185.41.212.sslip.io/,https://z8m99lkvh7u6ncfsumfwcrdl.151.185.41.212.sslip.io/auth,https://z8m99lkvh7u6ncfsumfwcrdl.151.185.41.212.sslip.io/auth/,http://localhost:5173/auth,http://localhost:5173/auth/,http://localhost:5173,http://localhost:5173/,http://localhost:3000,http://localhost:3000/");
                    clientAppRepository.save(client);
                },
                () -> {
                    log.info("Seeding bit-tool OAuth client...");
                    ClientApp client = ClientApp.builder()
                            .clientId("bit-tool")
                            .clientSecret("secure-bit-tool-secret-2026")
                            .appName("Bit Tool")
                            .redirectUri("https://www.bit-tool.com/auth,https://bit-tool.com/auth,https://www.bit-tool.com,https://bit-tool.com,https://app2.bit-tool.com,https://app2.bit-tool.com/,https://app2.bit-tool.com/auth,https://app2.bit-tool.com/auth/,http://app2.bit-tool.com,http://app2.bit-tool.com/,http://app2.bit-tool.com/auth,http://app2.bit-tool.com/auth/,https://z7ibjggnrtzynrguut8nerqr.151.185.41.212.sslip.io,https://z7ibjggnrtzynrguut8nerqr.151.185.41.212.sslip.io/,https://z7ibjggnrtzynrguut8nerqr.151.185.41.212.sslip.io/auth,https://z7ibjggnrtzynrguut8nerqr.151.185.41.212.sslip.io/auth/,http://z7ibjggnrtzynrguut8nerqr.151.185.41.212.sslip.io,http://z7ibjggnrtzynrguut8nerqr.151.185.41.212.sslip.io/,http://z7ibjggnrtzynrguut8nerqr.151.185.41.212.sslip.io/auth,http://z7ibjggnrtzynrguut8nerqr.151.185.41.212.sslip.io/auth/,https://z7m99lkvh7u6ncfsumfwcrdl.151.185.41.212.sslip.io,https://z7m99lkvh7u6ncfsumfwcrdl.151.185.41.212.sslip.io/,https://z7m99lkvh7u6ncfsumfwcrdl.151.185.41.212.sslip.io/auth,https://z7m99lkvh7u6ncfsumfwcrdl.151.185.41.212.sslip.io/auth/,http://z7m99lkvh7u6ncfsumfwcrdl.151.185.41.212.sslip.io,http://z7m99lkvh7u6ncfsumfwcrdl.151.185.41.212.sslip.io/,http://z7m99lkvh7u6ncfsumfwcrdl.151.185.41.212.sslip.io/auth,http://z7m99lkvh7u6ncfsumfwcrdl.151.185.41.212.sslip.io/auth/,https://z8m99lkvh7u6ncfsumfwcrdl.151.185.41.212.sslip.io,https://z8m99lkvh7u6ncfsumfwcrdl.151.185.41.212.sslip.io/,https://z8m99lkvh7u6ncfsumfwcrdl.151.185.41.212.sslip.io/auth,https://z8m99lkvh7u6ncfsumfwcrdl.151.185.41.212.sslip.io/auth/,http://localhost:5173/auth,http://localhost:5173/auth/,http://localhost:5173,http://localhost:5173/,http://localhost:3000,http://localhost:3000/")
                            .build();
                    clientAppRepository.save(client);
                    log.info("Test OAuth client seeded: bit-tool");
                }
            );

            clientAppRepository.findByClientId("account-ui").ifPresentOrElse(
                client -> {
                    log.info("Updating account-ui OAuth client redirect URI...");
                    client.setRedirectUri("https://account.beta-softnet.com,https://www.account.beta-softnet.com,https://accounts.beta-softnet.com,https://www.accounts.beta-softnet.com,https://fmvoaakg7zuldeooykytuirq.151.185.41.212.sslip.io,https://fmvoaakg7zuldeooykytuirq.151.185.41.212.sslip.io/,https://fmvoaakg7zuldeooykytuirq.151.185.41.212.sslip.io/auth,https://fmvoaakg7zuldeooykytuirq.151.185.41.212.sslip.io/auth/,http://fmvoaakg7zuldeooykytuirq.151.185.41.212.sslip.io,http://fmvoaakg7zuldeooykytuirq.151.185.41.212.sslip.io/,http://fmvoaakg7zuldeooykytuirq.151.185.41.212.sslip.io/auth,http://fmvoaakg7zuldeooykytuirq.151.185.41.212.sslip.io/auth/,https://y1qey7udat8pp09olovg98bu.151.185.41.212.sslip.io,https://y1qey7udat8pp09olovg98bu.151.185.41.212.sslip.io/,https://y1qey7udat8pp09olovg98bu.151.185.41.212.sslip.io/auth,https://y1qey7udat8pp09olovg98bu.151.185.41.212.sslip.io/auth/,http://y1qey7udat8pp09olovg98bu.151.185.41.212.sslip.io,http://y1qey7udat8pp09olovg98bu.151.185.41.212.sslip.io/,http://y1qey7udat8pp09olovg98bu.151.185.41.212.sslip.io/auth,http://y1qey7udat8pp09olovg98bu.151.185.41.212.sslip.io/auth/,http://localhost:5173,http://localhost:5173/,http://localhost:3000,http://localhost:3000/,http://localhost:3001,http://localhost:3001/");
                    clientAppRepository.save(client);
                },
                () -> {
                    log.info("Seeding account-ui OAuth client...");
                    ClientApp client = ClientApp.builder()
                            .clientId("account-ui")
                            .clientSecret("secure-account-secret-2026")
                            .appName("Account Settings")
                            .redirectUri("https://account.beta-softnet.com,https://www.account.beta-softnet.com,https://accounts.beta-softnet.com,https://www.accounts.beta-softnet.com,https://fmvoaakg7zuldeooykytuirq.151.185.41.212.sslip.io,https://fmvoaakg7zuldeooykytuirq.151.185.41.212.sslip.io/,https://fmvoaakg7zuldeooykytuirq.151.185.41.212.sslip.io/auth,https://fmvoaakg7zuldeooykytuirq.151.185.41.212.sslip.io/auth/,http://fmvoaakg7zuldeooykytuirq.151.185.41.212.sslip.io,http://fmvoaakg7zuldeooykytuirq.151.185.41.212.sslip.io/,http://fmvoaakg7zuldeooykytuirq.151.185.41.212.sslip.io/auth,http://fmvoaakg7zuldeooykytuirq.151.185.41.212.sslip.io/auth/,https://y1qey7udat8pp09olovg98bu.151.185.41.212.sslip.io,https://y1qey7udat8pp09olovg98bu.151.185.41.212.sslip.io/,https://y1qey7udat8pp09olovg98bu.151.185.41.212.sslip.io/auth,https://y1qey7udat8pp09olovg98bu.151.185.41.212.sslip.io/auth/,http://y1qey7udat8pp09olovg98bu.151.185.41.212.sslip.io,http://y1qey7udat8pp09olovg98bu.151.185.41.212.sslip.io/,http://y1qey7udat8pp09olovg98bu.151.185.41.212.sslip.io/auth,http://y1qey7udat8pp09olovg98bu.151.185.41.212.sslip.io/auth/,http://localhost:5173,http://localhost:5173/,http://localhost:3000,http://localhost:3000/,http://localhost:3001,http://localhost:3001/")
                            .build();
                    clientAppRepository.save(client);
                    log.info("Test OAuth client seeded: account-ui");
                }
            );

            clientAppRepository.findByClientId("beta-storage").ifPresentOrElse(
                client -> {
                    log.info("Updating beta-storage OAuth client redirect URI...");
                    client.setRedirectUri("https://storage.beta-softnet.com,https://www.storage.beta-softnet.com,https://storage.beta-softnet.com/,https://z7ibjggnrtzynrguut8nerqr.151.185.41.212.sslip.io,https://z7ibjggnrtzynrguut8nerqr.151.185.41.212.sslip.io/,https://z7ibjggnrtzynrguut8nerqr.151.185.41.212.sslip.io/auth,https://z7ibjggnrtzynrguut8nerqr.151.185.41.212.sslip.io/auth/,http://z7ibjggnrtzynrguut8nerqr.151.185.41.212.sslip.io,http://z7ibjggnrtzynrguut8nerqr.151.185.41.212.sslip.io/,http://z7ibjggnrtzynrguut8nerqr.151.185.41.212.sslip.io/auth,http://z7ibjggnrtzynrguut8nerqr.151.185.41.212.sslip.io/auth/,https://a11atsvnjorf7fwsxsfxvkwf.151.185.41.212.sslip.io,https://a11atsvnjorf7fwsxsfxvkwf.151.185.41.212.sslip.io/,https://a11atsvnjorf7fwsxsfxvkwf.151.185.41.212.sslip.io/auth,http://a11atsvnjorf7fwsxsfxvkwf.151.185.41.212.sslip.io,http://a11atsvnjorf7fwsxsfxvkwf.151.185.41.212.sslip.io/,http://a11atsvnjorf7fwsxsfxvkwf.151.185.41.212.sslip.io/auth,https://t2da8odkjkrmgtktzvcjidwy.151.185.41.212.sslip.io,https://t2da8odkjkrmgtktzvcjidwy.151.185.41.212.sslip.io/,https://t2da8odkjkrmgtktzvcjidwy.151.185.41.212.sslip.io/auth,https://t2da8odkjkrmgtktzvcjidwy.151.185.41.212.sslip.io/auth/,http://t2da8odkjkrmgtktzvcjidwy.151.185.41.212.sslip.io,http://t2da8odkjkrmgtktzvcjidwy.151.185.41.212.sslip.io/,http://t2da8odkjkrmgtktzvcjidwy.151.185.41.212.sslip.io/auth,http://t2da8odkjkrmgtktzvcjidwy.151.185.41.212.sslip.io/auth/,http://localhost:5173,http://localhost:5173/,http://localhost:3000,http://localhost:3000/");
                    clientAppRepository.save(client);
                },
                () -> {
                    log.info("Seeding beta-storage OAuth client...");
                    ClientApp client = ClientApp.builder()
                            .clientId("beta-storage")
                            .clientSecret("secure-storage-secret-2026")
                            .appName("Beta Storage")
                            .redirectUri("https://storage.beta-softnet.com,https://www.storage.beta-softnet.com,https://storage.beta-softnet.com/,https://z7ibjggnrtzynrguut8nerqr.151.185.41.212.sslip.io,https://z7ibjggnrtzynrguut8nerqr.151.185.41.212.sslip.io/,https://z7ibjggnrtzynrguut8nerqr.151.185.41.212.sslip.io/auth,https://z7ibjggnrtzynrguut8nerqr.151.185.41.212.sslip.io/auth/,http://z7ibjggnrtzynrguut8nerqr.151.185.41.212.sslip.io,http://z7ibjggnrtzynrguut8nerqr.151.185.41.212.sslip.io/,http://z7ibjggnrtzynrguut8nerqr.151.185.41.212.sslip.io/auth,http://z7ibjggnrtzynrguut8nerqr.151.185.41.212.sslip.io/auth/,https://a11atsvnjorf7fwsxsfxvkwf.151.185.41.212.sslip.io,https://a11atsvnjorf7fwsxsfxvkwf.151.185.41.212.sslip.io/,https://a11atsvnjorf7fwsxsfxvkwf.151.185.41.212.sslip.io/auth,http://a11atsvnjorf7fwsxsfxvkwf.151.185.41.212.sslip.io,http://a11atsvnjorf7fwsxsfxvkwf.151.185.41.212.sslip.io/,http://a11atsvnjorf7fwsxsfxvkwf.151.185.41.212.sslip.io/auth,https://t2da8odkjkrmgtktzvcjidwy.151.185.41.212.sslip.io,https://t2da8odkjkrmgtktzvcjidwy.151.185.41.212.sslip.io/,https://t2da8odkjkrmgtktzvcjidwy.151.185.41.212.sslip.io/auth,https://t2da8odkjkrmgtktzvcjidwy.151.185.41.212.sslip.io/auth/,http://t2da8odkjkrmgtktzvcjidwy.151.185.41.212.sslip.io,http://t2da8odkjkrmgtktzvcjidwy.151.185.41.212.sslip.io/,http://t2da8odkjkrmgtktzvcjidwy.151.185.41.212.sslip.io/auth,http://t2da8odkjkrmgtktzvcjidwy.151.185.41.212.sslip.io/auth/,http://localhost:5173,http://localhost:5173/,http://localhost:3000,http://localhost:3000/")
                            .build();
                    clientAppRepository.save(client);
                    log.info("Test OAuth client seeded: beta-storage");
                }
            );
        };
    }
}
