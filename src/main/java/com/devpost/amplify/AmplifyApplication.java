package com.devpost.amplify;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@ImportAutoConfiguration(
		exclude = {
				org.springframework.ai.model.vertexai.autoconfigure.embedding.VertexAiEmbeddingConnectionAutoConfiguration.class
		}
)
public class AmplifyApplication {

	public static void main(String[] args) {
		SpringApplication.run(AmplifyApplication.class, args);
	}

}
