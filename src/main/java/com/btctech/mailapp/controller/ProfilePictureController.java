package com.btctech.mailapp.controller;

import com.btctech.mailapp.config.JwtUtil;
import com.btctech.mailapp.dto.ApiResponse;
import com.btctech.mailapp.entity.MailAccount;
import com.btctech.mailapp.entity.User;
import com.btctech.mailapp.repository.MailAccountRepository;
import com.btctech.mailapp.repository.UserRepository;
import com.btctech.mailapp.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ProfilePictureController {

    private final UserRepository userRepository;
    private final MailAccountRepository mailAccountRepository;
    private final UserService userService;
    private final JwtUtil jwtUtil;

    private static final String UPLOAD_DIR = "uploads/profile-pictures";
    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024; // 5 MB

    /**
     * POST: Upload a profile picture for the main user profile
     */
    @PostMapping("/users/profile-picture")
    public ResponseEntity<ApiResponse<Map<String, Object>>> uploadUserProfilePicture(
            @RequestParam("file") MultipartFile file,
            @RequestHeader("Authorization") String authHeader) {

        try {
            String token = authHeader.replace("Bearer ", "");
            String identifier = jwtUtil.extractEmail(token);
            User user = userService.getUserByEmailOrUsername(identifier);

            validateFile(file);

            // Create upload directory if not exists
            Path uploadPath = Paths.get(UPLOAD_DIR);
            if (!Files.exists(uploadPath)) {
                Files.createDirectories(uploadPath);
            }

            // Delete old file if exists
            if (user.getProfilePicture() != null) {
                try {
                    Files.deleteIfExists(uploadPath.resolve(user.getProfilePicture()));
                } catch (IOException e) {
                    log.warn("Could not delete old profile picture file: {}", e.getMessage());
                }
            }

            // Save new file
            String extension = getFileExtension(file.getOriginalFilename());
            String fileName = "user_" + user.getId() + "_" + System.currentTimeMillis() + extension;
            Path targetLocation = uploadPath.resolve(fileName);
            Files.copy(file.getInputStream(), targetLocation, StandardCopyOption.REPLACE_EXISTING);

            // Update user entity
            user.setProfilePicture(fileName);
            userRepository.save(user);

            Map<String, Object> data = new HashMap<>();
            data.put("profilePicture", fileName);
            data.put("profilePictureUrl", "/api/users/profile-picture/" + user.getUsername());

            return ResponseEntity.ok(ApiResponse.success(data, "Profile picture uploaded successfully"));

        } catch (Exception e) {
            log.error("Failed to upload user profile picture: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(ApiResponse.error("Failed to upload profile picture: " + e.getMessage()));
        }
    }

    /**
     * DELETE: Delete the user's main profile picture
     */
    @DeleteMapping("/users/profile-picture")
    public ResponseEntity<ApiResponse<Void>> deleteUserProfilePicture(
            @RequestHeader("Authorization") String authHeader) {

        try {
            String token = authHeader.replace("Bearer ", "");
            String identifier = jwtUtil.extractEmail(token);
            User user = userService.getUserByEmailOrUsername(identifier);

            if (user.getProfilePicture() != null) {
                Path filePath = Paths.get(UPLOAD_DIR).resolve(user.getProfilePicture());
                Files.deleteIfExists(filePath);
                user.setProfilePicture(null);
                userRepository.save(user);
            }

            return ResponseEntity.ok(ApiResponse.success(null, "Profile picture deleted successfully"));
        } catch (Exception e) {
            log.error("Failed to delete user profile picture: {}", e.getMessage());
            return ResponseEntity.badRequest().body(ApiResponse.error("Failed to delete profile picture: " + e.getMessage()));
        }
    }

    /**
     * POST: Upload a profile picture for a specific email account (identity)
     */
    @PostMapping("/emails/{emailId}/profile-picture")
    public ResponseEntity<ApiResponse<Map<String, Object>>> uploadEmailProfilePicture(
            @PathVariable Long emailId,
            @RequestParam("file") MultipartFile file,
            @RequestHeader("Authorization") String authHeader) {

        try {
            String token = authHeader.replace("Bearer ", "");
            String identifier = jwtUtil.extractEmail(token);
            User user = userService.getUserByEmailOrUsername(identifier);

            MailAccount account = mailAccountRepository.findById(emailId)
                    .orElseThrow(() -> new IllegalArgumentException("Mail account not found"));

            if (!account.getUserId().equals(user.getId())) {
                return ResponseEntity.status(403).body(ApiResponse.error("Access denied. You do not own this email account."));
            }

            validateFile(file);

            // Create upload directory if not exists
            Path uploadPath = Paths.get(UPLOAD_DIR);
            if (!Files.exists(uploadPath)) {
                Files.createDirectories(uploadPath);
            }

            // Delete old file if exists
            if (account.getProfilePicture() != null) {
                try {
                    Files.deleteIfExists(uploadPath.resolve(account.getProfilePicture()));
                } catch (IOException e) {
                    log.warn("Could not delete old email profile picture file: {}", e.getMessage());
                }
            }

            // Save new file
            String extension = getFileExtension(file.getOriginalFilename());
            String fileName = "mail_" + account.getId() + "_" + System.currentTimeMillis() + extension;
            Path targetLocation = uploadPath.resolve(fileName);
            Files.copy(file.getInputStream(), targetLocation, StandardCopyOption.REPLACE_EXISTING);

            // Update mail account
            account.setProfilePicture(fileName);
            mailAccountRepository.save(account);

            Map<String, Object> data = new HashMap<>();
            data.put("profilePicture", fileName);
            data.put("profilePictureUrl", "/api/users/profile-picture/" + account.getEmail());

            return ResponseEntity.ok(ApiResponse.success(data, "Email profile picture uploaded successfully"));

        } catch (Exception e) {
            log.error("Failed to upload email profile picture: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(ApiResponse.error("Failed to upload profile picture: " + e.getMessage()));
        }
    }

    /**
     * DELETE: Delete a specific email account's profile picture
     */
    @DeleteMapping("/emails/{emailId}/profile-picture")
    public ResponseEntity<ApiResponse<Void>> deleteEmailProfilePicture(
            @PathVariable Long emailId,
            @RequestHeader("Authorization") String authHeader) {

        try {
            String token = authHeader.replace("Bearer ", "");
            String identifier = jwtUtil.extractEmail(token);
            User user = userService.getUserByEmailOrUsername(identifier);

            MailAccount account = mailAccountRepository.findById(emailId)
                    .orElseThrow(() -> new IllegalArgumentException("Mail account not found"));

            if (!account.getUserId().equals(user.getId())) {
                return ResponseEntity.status(403).body(ApiResponse.error("Access denied. You do not own this email account."));
            }

            if (account.getProfilePicture() != null) {
                Path filePath = Paths.get(UPLOAD_DIR).resolve(account.getProfilePicture());
                Files.deleteIfExists(filePath);
                account.setProfilePicture(null);
                mailAccountRepository.save(account);
            }

            return ResponseEntity.ok(ApiResponse.success(null, "Email profile picture deleted successfully"));
        } catch (Exception e) {
            log.error("Failed to delete email profile picture: {}", e.getMessage());
            return ResponseEntity.badRequest().body(ApiResponse.error("Failed to delete profile picture: " + e.getMessage()));
        }
    }

    /**
     * GET: Public retrieval of profile picture. Resolves mail account first, then falls back to user.
     * Serves dynamic SVG initials avatar if none is set.
     */
    @GetMapping("/users/profile-picture/{usernameOrEmail:.+}")
    public ResponseEntity<?> getProfilePicture(
            @PathVariable String usernameOrEmail,
            @RequestParam(value = "fallback", required = false, defaultValue = "false") boolean fallback) {
        try {
            String profilePictureFilename = null;
            String displayName = usernameOrEmail;

            if (usernameOrEmail.contains("@")) {
                // Email identifier
                Optional<MailAccount> accountOpt = mailAccountRepository.findByEmail(usernameOrEmail);
                if (accountOpt.isPresent()) {
                    MailAccount account = accountOpt.get();
                    displayName = account.getEmailName();
                    if (account.getProfilePicture() != null) {
                        profilePictureFilename = account.getProfilePicture();
                    } else {
                        // Fallback to owner user avatar
                        Optional<User> ownerOpt = userRepository.findById(account.getUserId());
                        if (ownerOpt.isPresent() && ownerOpt.get().getProfilePicture() != null) {
                            profilePictureFilename = ownerOpt.get().getProfilePicture();
                            displayName = ownerOpt.get().getFirstName();
                        }
                    }
                } else {
                    // Try to look up User by main email
                    Optional<User> userOpt = userRepository.findByEmail(usernameOrEmail);
                    if (userOpt.isPresent()) {
                        User user = userOpt.get();
                        displayName = user.getFirstName();
                        if (user.getProfilePicture() != null) {
                            profilePictureFilename = user.getProfilePicture();
                        }
                    }
                }
            } else if (usernameOrEmail.matches("\\d+")) {
                // User ID identifier
                Optional<User> userOpt = userRepository.findById(Long.parseLong(usernameOrEmail));
                if (userOpt.isPresent()) {
                    User user = userOpt.get();
                    displayName = user.getFirstName() != null ? user.getFirstName() : user.getUsername();
                    if (user.getProfilePicture() != null) {
                        profilePictureFilename = user.getProfilePicture();
                    }
                }
            } else {
                // Username identifier
                Optional<User> userOpt = userRepository.findByUsername(usernameOrEmail);
                if (userOpt.isPresent()) {
                    User user = userOpt.get();
                    displayName = user.getFirstName();
                    if (user.getProfilePicture() != null) {
                        profilePictureFilename = user.getProfilePicture();
                    }
                }
            }

            if (profilePictureFilename != null) {
                Path filePath = Paths.get(UPLOAD_DIR).resolve(profilePictureFilename);
                File file = filePath.toFile();

                if (file.exists() && file.isFile()) {
                    Resource resource = new UrlResource(filePath.toUri());
                    String contentType = Files.probeContentType(filePath);
                    if (contentType == null) {
                        contentType = "image/jpeg";
                    }

                    return ResponseEntity.ok()
                            .contentType(MediaType.parseMediaType(contentType))
                            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + file.getName() + "\"")
                            .header(HttpHeaders.CACHE_CONTROL, "public, max-age=3600")
                            .body(resource);
                }
            }

            // If no profile picture or file does not exist:
            if (fallback) {
                return serveDefaultAvatar(displayName);
            }
            return ResponseEntity.notFound().build();

        } catch (Exception e) {
            log.error("Error retrieving profile picture for {}: {}", usernameOrEmail, e.getMessage());
            if (fallback) {
                return serveDefaultAvatar(usernameOrEmail);
            }
            return ResponseEntity.notFound().build();
        }
    }

    private void validateFile(MultipartFile file) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file cannot be empty");
        }

        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("File size exceeds 5MB limit");
        }

        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new IllegalArgumentException("Only image files are allowed");
        }
    }

    private String getFileExtension(String fileName) {
        if (fileName == null) return ".jpg";
        int lastIndex = fileName.lastIndexOf('.');
        if (lastIndex == -1) return ".jpg";
        return fileName.substring(lastIndex);
    }

    private ResponseEntity<byte[]> serveDefaultAvatar(String name) {
        String initial = (name != null && !name.isEmpty()) ? name.substring(0, 1).toUpperCase() : "U";

        // Pick a nice color based on the initial
        String[] colors = {
                "#F87171", "#EC4899", "#D946EF", "#A855F7",
                "#6366F1", "#3B82F6", "#0EA5E9", "#14B8A6",
                "#10B981", "#22C55E", "#F59E0B", "#F97316"
        };
        int colorIndex = Math.abs(initial.hashCode()) % colors.length;
        String color = colors[colorIndex];

        String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 100 100\" width=\"100\" height=\"100\">" +
                "<rect width=\"100%\" height=\"100%\" fill=\"" + color + "\"/>" +
                "<text x=\"50%\" y=\"50%\" dominant-baseline=\"middle\" text-anchor=\"middle\" fill=\"#ffffff\" font-size=\"45\" font-family=\"Arial, sans-serif\" font-weight=\"bold\">" +
                initial + "</text></svg>";

        return ResponseEntity.ok()
                .contentType(MediaType.valueOf("image/svg+xml"))
                .body(svg.getBytes());
    }
}
