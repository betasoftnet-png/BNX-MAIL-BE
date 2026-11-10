package com.btctech.mailapp.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserSettingsDTO {
    private String phoneNumber;
    private String location;
    private String jobTitle;

    private Boolean inboxNotifications;
    private Boolean sentNotifications;
    private Boolean starredNotifications;
    private Boolean snoozedNotifications;
    private Boolean soundEnabled;
    private Boolean vibrationEnabled;
    private Boolean quietHoursEnabled;
    private String quietHoursStart;
    private String quietHoursEnd;

    private String themeMode;
    private String accentColor;
    private Double fontSize;
    private String density;
    private String wallpaper;
    
    private String profilePictureUrl;
    
    private Long storageLimit;
    private Long storageUsed;

    private Boolean twoFactorEnabled;
    private Boolean biometricsEnabled;
    private String language;
    
    private Integer undoSendDelay;
    
    private Boolean spellingCheckEnabled;
    private Boolean grammarCheckEnabled;
    private Boolean autoCorrectEnabled;
    private Boolean smartComposeEnabled;
    
    private String readingPaneMode;

    // Default Text Style Settings
    private String fontFamily;
    private String textStyleFontSize;
    private String textColor;

    private List<String> casboxAccepted;
}
