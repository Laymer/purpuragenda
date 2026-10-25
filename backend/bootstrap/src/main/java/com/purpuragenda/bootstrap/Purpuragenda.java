package com.purpuragenda.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages="com.purpuragenda")
@EnableScheduling
public class Purpuragenda {
    public static void main(String[] args) {
        boolean command=args.length>0&&(args[0].equals("admin")||args[0].equals("migrate"));
        SpringApplication app=new SpringApplication(Purpuragenda.class);
        if(command){app.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE);System.setProperty("app.jobs.enabled","false");}
        var context=app.run(args);if(command)context.close();
    }
}
