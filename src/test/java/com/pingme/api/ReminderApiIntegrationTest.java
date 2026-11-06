package com.pingme.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.ObjectMapper;
import com.pingme.reminder.dto.CreateReminderRequest;
import com.pingme.user.dto.CreateUserRequest;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = "pingme.scheduler.enabled=false")
@AutoConfigureMockMvc
@Testcontainers
class ReminderApiIntegrationTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void reminderCrudFlow() throws Exception {
		CreateUserRequest userRequest = new CreateUserRequest("Ada Lovelace", "ada@example.com");
		MvcResult userResult = mockMvc.perform(post("/users")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(userRequest)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").isNumber())
				.andExpect(jsonPath("$.email").value("ada@example.com"))
				.andReturn();

		long userId = objectMapper.readTree(userResult.getResponse().getContentAsString()).get("id").asLong();

		Instant scheduledTime = Instant.parse("2030-01-15T10:00:00Z");
		CreateReminderRequest reminderRequest =
				new CreateReminderRequest(userId, "Call John", "Discuss project", scheduledTime);

		MvcResult createResult = mockMvc.perform(post("/reminders")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(reminderRequest)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("PENDING"))
				.andExpect(jsonPath("$.userId").value(userId))
				.andReturn();

		long reminderId =
				objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asLong();

		mockMvc.perform(get("/reminders/{id}", reminderId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.title").value("Call John"));

		mockMvc.perform(get("/reminders").param("userId", String.valueOf(userId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1));

		mockMvc.perform(delete("/reminders/{id}", reminderId)).andExpect(status().isNoContent());

		mockMvc.perform(get("/reminders/{id}", reminderId)).andExpect(status().isNotFound());
	}

	@Test
	void createReminderForMissingUserReturns404() throws Exception {
		CreateReminderRequest request = new CreateReminderRequest(999_999L, "Title", null, Instant.now());
		mockMvc.perform(post("/reminders")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(request)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.status").value(404));
	}

	@Test
	void duplicateEmailReturns409() throws Exception {
		CreateUserRequest request = new CreateUserRequest("Test User", "dup@example.com");
		String body = objectMapper.writeValueAsString(request);

		mockMvc.perform(post("/users").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated());

		mockMvc.perform(post("/users").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isConflict());
	}

}
