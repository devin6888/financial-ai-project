package com.finagent;

import com.finagent.context.ContextProperties;
import com.finagent.document.ParserProperties;
import com.finagent.scope.ScopeProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({ParserProperties.class, ScopeProperties.class,
        ContextProperties.class})
public class FinAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(FinAgentApplication.class, args);
    }
}
