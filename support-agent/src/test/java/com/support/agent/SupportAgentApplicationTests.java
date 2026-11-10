package com.support.agent;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// A dead inbox address, so starting the app in a test never handles real mail.
@SpringBootTest(properties = "support-agent.inbox.base-url=http://127.0.0.1:9")
class SupportAgentApplicationTests {

	@Test
	void contextLoads() {
	}

}
