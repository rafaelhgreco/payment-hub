package com.v1.paymenthub;

import org.springframework.boot.SpringApplication;

public class TestPaymenthubApplication {

	public static void main(String[] args) {
		SpringApplication.from(PaymenthubApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
