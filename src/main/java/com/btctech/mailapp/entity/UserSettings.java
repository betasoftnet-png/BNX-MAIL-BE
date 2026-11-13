package com.btctech.mailapp.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "user_settings")
public class UserSettings {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", unique = true, nullable = false)
    private User user;

    // Personal Info Extensions
    private String phoneNumber;
    private String location;
    private String jobTitle;

    // Notification Settings
    @Builder.Default
    private Boolean inboxNotifications = true;
    @Builder.Default
    private Boolean sentNotifications = false;
    @Builder.Default
    private Boolean starredNotifications = true;
    @Builder.Default
    private Boolean snoozedNotifications = true;
    @Builder.Default
    private Boolean soundEnabled = true;
    @Builder.Default
    private Boolean vibrationEnabled = true;
    @Builder.Default
    private Boolean quietHoursEnabled = false;
    @Builder.Default
    private String quietHoursStart = "22:00";
    @Builder.Default
    private String quietHoursEnd = "07:00";

    // Appearance Settings
    @Builder.Default
    private String themeMode = "Classic";
    @Builder.Default
    private String accentColor = "#4F46E5"; // Default primary color
    @Builder.Default
    private Double fontSize = 1.0;
    @Builder.Default
    private String density = "Default"; // Compact, Default, Comfortable
    @Builder.Default
    @Column(name = "wallpaper")
    private String wallpaper = "default";

    @Builder.Default
    private Long storageLimit = 16106127360L; // 15 GB in bytes

    @Builder.Default
    private Boolean twoFactorEnabled = false;

    @Builder.Default
    private Boolean biometricsEnabled = true;

    @Builder.Default
    private String language = "en_US";

    // Composing Settings
    @Builder.Default
    private Integer undoSendDelay = 0;

    @Builder.Default
    private Boolean spellingCheckEnabled = true;

    @Builder.Default
    private Boolean grammarCheckEnabled = true;

    @Builder.Default
    private Boolean autoCorrectEnabled = true;

    @Builder.Default
    private Boolean smartComposeEnabled = true;

    // Layout Settings
    @Builder.Default
    private String readingPaneMode = "no_split";

    // Default Text Style Settings
    @Builder.Default
    @Column(name = "font_family")
    private String fontFamily = "Arial";

    @Builder.Default
    @Column(name = "text_style_font_size")
    private String textStyleFontSize = "Normal";

    @Builder.Default
    @Column(name = "text_color")
    private String textColor = "#000000";

    // Casbox Settings
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_casbox_accepted", joinColumns = @JoinColumn(name = "user_settings_id"))
    @Column(name = "email")
    @Builder.Default
    private List<String> casboxAccepted = new java.util.ArrayList<>();
}
