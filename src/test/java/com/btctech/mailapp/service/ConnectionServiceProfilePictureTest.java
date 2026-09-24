package com.btctech.mailapp.service;

import com.btctech.mailapp.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

class ConnectionServiceProfilePictureTest {

    @Test
    @DisplayName("resolveProfilePictureUrl returns null when user is null or profilePicture is null/empty")
    void testNullOrEmptyProfilePicture() {
        ConnectionService service = new ConnectionService(null, null, null, null, null);

        assertNull(service.resolveProfilePictureUrl(null));

        User user = new User();
        user.setUsername("rahulram042");
        user.setProfilePicture(null);
        assertNull(service.resolveProfilePictureUrl(user));

        user.setProfilePicture("   ");
        assertNull(service.resolveProfilePictureUrl(user));
    }

    @Test
    @DisplayName("resolveProfilePictureUrl preserves http/https/data URLs")
    void testExternalUrls() {
        ConnectionService service = new ConnectionService(null, null, null, null, null);

        User user = new User();
        user.setUsername("rahulram042");

        user.setProfilePicture("https://example.com/avatar.png");
        assertEquals("https://example.com/avatar.png", service.resolveProfilePictureUrl(user));

        user.setProfilePicture("http://example.com/avatar.png");
        assertEquals("http://example.com/avatar.png", service.resolveProfilePictureUrl(user));

        user.setProfilePicture("data:image/png;base64,iVBORw0KGgoAAAANSUhEUg==");
        assertEquals("data:image/png;base64,iVBORw0KGgoAAAANSUhEUg==", service.resolveProfilePictureUrl(user));
    }

    @Test
    @DisplayName("resolveProfilePictureUrl returns null if stored file does not exist on disk")
    void testNonExistentFileReturnsNull() {
        ConnectionService service = new ConnectionService(null, null, null, null, null);

        User user = new User();
        user.setUsername("rahulram042");
        user.setProfilePicture("non_existent_file_999999.jpg");

        assertNull(service.resolveProfilePictureUrl(user));
    }

    @Test
    @DisplayName("resolveProfilePictureUrl returns /api/users/profile-picture/{username} when file exists")
    void testExistingFileReturnsUrl() throws Exception {
        ConnectionService service = new ConnectionService(null, null, null, null, null);

        Path uploadDir = Paths.get("uploads/profile-pictures");
        Files.createDirectories(uploadDir);
        Path tempFile = uploadDir.resolve("test_user_profile_unit_test.jpg");
        Files.write(tempFile, new byte[]{1, 2, 3});

        try {
            User user = new User();
            user.setUsername("rahulram042");
            user.setProfilePicture("test_user_profile_unit_test.jpg");

            String url = service.resolveProfilePictureUrl(user);
            assertEquals("/api/users/profile-picture/rahulram042", url);
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }
}
