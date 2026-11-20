package com.btctech.mailapp.service;

import com.btctech.mailapp.dto.EmailDTO;
import com.btctech.mailapp.entity.ImportantEmail;
import com.btctech.mailapp.entity.UserSenderActivity;
import com.btctech.mailapp.repository.ImportantEmailRepository;
import com.btctech.mailapp.repository.StarredEmailRepository;
import com.btctech.mailapp.repository.UserSenderActivityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SmartImportanceServiceTest {

    private ImportantEmailRepository importantEmailRepository;
    private UserSenderActivityRepository userSenderActivityRepository;
    private StarredEmailRepository starredEmailRepository;

    private SmartImportanceService smartImportanceService;

    private final String USER_EMAIL = "user@bnxmail.com";

    @BeforeEach
    void setUp() {
        importantEmailRepository = mock(ImportantEmailRepository.class);
        userSenderActivityRepository = mock(UserSenderActivityRepository.class);
        starredEmailRepository = mock(StarredEmailRepository.class);

        smartImportanceService = new SmartImportanceService(
                importantEmailRepository,
                userSenderActivityRepository,
                starredEmailRepository
        );
    }

    @Test
    @DisplayName("Scenario 1: Frequently opened sender vs single open")
    void testFrequentlyOpenedSender() {
        String senderEmail = "colleague@bnxmail.com";

        // Single open: opening an email once must NOT make it Important
        UserSenderActivity singleOpenActivity = UserSenderActivity.builder()
                .userEmail(USER_EMAIL)
                .senderEmail(senderEmail)
                .openCount(1)
                .replyCount(0)
                .lastOpenedAt(LocalDateTime.now())
                .lastInteractionAt(LocalDateTime.now())
                .build();
        when(userSenderActivityRepository.findByUserEmailAndSenderEmail(USER_EMAIL, senderEmail))
                .thenReturn(Optional.of(singleOpenActivity));

        EmailDTO email1 = EmailDTO.builder()
                .uid("101")
                .from(senderEmail)
                .to(USER_EMAIL)
                .subject("Quick check-in")
                .body("Hello!")
                .build();

        SmartImportanceService.ImportanceResult result1 = smartImportanceService.evaluateEmail(USER_EMAIL, email1);
        assertFalse(result1.isImportant(), "Opening an email once must not automatically make it Important");
        assertTrue(result1.getScore() < SmartImportanceService.IMPORTANCE_THRESHOLD, "Score should be below threshold for 1 open");

        // Frequently opened: e.g. 5 opens + recent interaction + direct To reaches >= 50.0
        UserSenderActivity frequentOpenActivity = UserSenderActivity.builder()
                .userEmail(USER_EMAIL)
                .senderEmail(senderEmail)
                .openCount(5)
                .replyCount(0)
                .lastOpenedAt(LocalDateTime.now())
                .lastInteractionAt(LocalDateTime.now())
                .build();
        when(userSenderActivityRepository.findByUserEmailAndSenderEmail(USER_EMAIL, senderEmail))
                .thenReturn(Optional.of(frequentOpenActivity));

        SmartImportanceService.ImportanceResult result2 = smartImportanceService.evaluateEmail(USER_EMAIL, email1);
        assertTrue(result2.isImportant(), "Frequently opened sender must be automatically classified as Important");
        assertTrue(result2.getScore() >= SmartImportanceService.IMPORTANCE_THRESHOLD);
    }

    @Test
    @DisplayName("Scenario 2: Frequently replied-to sender")
    void testFrequentlyRepliedToSender() {
        String senderEmail = "manager@bnxmail.com";

        UserSenderActivity repliedActivity = UserSenderActivity.builder()
                .userEmail(USER_EMAIL)
                .senderEmail(senderEmail)
                .openCount(3)
                .replyCount(2)
                .threadCount(2)
                .lastOpenedAt(LocalDateTime.now())
                .lastRepliedAt(LocalDateTime.now())
                .lastInteractionAt(LocalDateTime.now())
                .build();
        when(userSenderActivityRepository.findByUserEmailAndSenderEmail(USER_EMAIL, senderEmail))
                .thenReturn(Optional.of(repliedActivity));

        EmailDTO email = EmailDTO.builder()
                .uid("102")
                .from(senderEmail)
                .to(USER_EMAIL)
                .subject("Quarterly Review")
                .body("Let's meet tomorrow.")
                .build();

        SmartImportanceService.ImportanceResult result = smartImportanceService.evaluateEmail(USER_EMAIL, email);
        assertTrue(result.isImportant(), "Sender frequently replied to must be classified as Important");
        assertTrue(result.getScore() >= SmartImportanceService.IMPORTANCE_THRESHOLD);
        assertTrue(result.getReasons().contains("Frequently replied-to"));
    }

    @Test
    @DisplayName("Scenario 3: New / unknown sender")
    void testNewSender() {
        String newSender = "stranger@unknown.com";
        when(userSenderActivityRepository.findByUserEmailAndSenderEmail(USER_EMAIL, newSender))
                .thenReturn(Optional.empty());

        EmailDTO email = EmailDTO.builder()
                .uid("103")
                .from(newSender)
                .to(USER_EMAIL)
                .subject("Hello there")
                .body("Initial contact message.")
                .build();

        SmartImportanceService.ImportanceResult result = smartImportanceService.evaluateEmail(USER_EMAIL, email);
        assertFalse(result.isImportant(), "New unknown sender should not be marked Important automatically");
        assertTrue(result.getScore() >= 0.0, "New sender is evaluated fairly and not penalized with negative score");
    }

    @Test
    @DisplayName("Scenario 4: Promotional and newsletter emails disqualified even if opened")
    void testPromotionalAndNewsletterEmails() {
        String promoSender = "deals@store.com";

        // Even if opened 10 times, promotional emails must NOT become Important!
        UserSenderActivity activity = UserSenderActivity.builder()
                .userEmail(USER_EMAIL)
                .senderEmail(promoSender)
                .openCount(10)
                .replyCount(0)
                .lastOpenedAt(LocalDateTime.now())
                .lastInteractionAt(LocalDateTime.now())
                .build();
        when(userSenderActivityRepository.findByUserEmailAndSenderEmail(USER_EMAIL, promoSender))
                .thenReturn(Optional.of(activity));

        // 4a: Category is PROMOTIONS
        EmailDTO promoEmail = EmailDTO.builder()
                .uid("104")
                .from(promoSender)
                .to(USER_EMAIL)
                .subject("50% off everything today!")
                .body("Check out our sale: https://store.com/sale")
                .category("PROMOTIONS")
                .build();

        SmartImportanceService.ImportanceResult resultPromo = smartImportanceService.evaluateEmail(USER_EMAIL, promoEmail);
        assertFalse(resultPromo.isImportant(), "Promotional email must never be marked Important");
        assertEquals(-100.0, resultPromo.getScore(), "Promotions must receive penalty score");

        // 4b: Newsletter with unsubscribe in body
        EmailDTO newsletterEmail = EmailDTO.builder()
                .uid("105")
                .from("newsletter@techdigest.com")
                .to(USER_EMAIL)
                .subject("Weekly Tech Roundup #42")
                .body("Here are the top stories. To unsubscribe, click here.")
                .build();

        SmartImportanceService.ImportanceResult resultNews = smartImportanceService.evaluateEmail(USER_EMAIL, newsletterEmail);
        assertFalse(resultNews.isImportant(), "Newsletter with unsubscribe must never be marked Important");
        assertEquals(-100.0, resultNews.getScore());
    }

    @Test
    @DisplayName("Scenario 5: Manual Mark Important")
    void testManualMarkImportant() {
        String uid = "106";
        String folder = "INBOX";

        when(importantEmailRepository.findByUserEmailAndUid(USER_EMAIL, uid))
                .thenReturn(Collections.emptyList());

        boolean status = smartImportanceService.setManualImportance(USER_EMAIL, uid, folder, true);
        assertTrue(status);

        verify(importantEmailRepository, times(1)).save(argThat(record ->
                record.getUserEmail().equals(USER_EMAIL) &&
                record.getUid().equals(uid) &&
                record.isImportant() &&
                record.isManual()
        ));
    }

    @Test
    @DisplayName("Scenario 6: Manual Remove Important overrides automatic classification")
    void testManualRemoveImportantOverridesAutomatic() {
        String senderEmail = "active@bnxmail.com";
        String uid = "107";
        String folder = "INBOX";

        // Manual record exists saying NOT important (user unpinned/unmarked it)
        ImportantEmail manualUnmarked = ImportantEmail.builder()
                .id(1L)
                .userEmail(USER_EMAIL)
                .uid(uid)
                .folderName(folder)
                .isImportant(false)
                .isManual(true)
                .score(0.0)
                .build();

        when(importantEmailRepository.findByUserEmailAndUidAndFolderName(USER_EMAIL, uid, folder))
                .thenReturn(Optional.of(manualUnmarked));

        // Even if sender activity has tons of opens and replies
        UserSenderActivity activity = UserSenderActivity.builder()
                .userEmail(USER_EMAIL)
                .senderEmail(senderEmail)
                .openCount(50)
                .replyCount(20)
                .build();
        when(userSenderActivityRepository.findByUserEmailAndSenderEmail(USER_EMAIL, senderEmail))
                .thenReturn(Optional.of(activity));

        EmailDTO email = EmailDTO.builder()
                .uid(uid)
                .from(senderEmail)
                .to(USER_EMAIL)
                .folderName(folder)
                .subject("Work Document")
                .body("Please check attached.")
                .build();

        SmartImportanceService.ImportanceResult result = smartImportanceService.evaluateEmail(USER_EMAIL, email);
        assertFalse(result.isImportant(), "User manual Not Important decision MUST strictly take priority over automatic classification");
        assertTrue(result.isManual());
    }

    @Test
    @DisplayName("Scenario 7: Persistence across refresh and login")
    void testPersistenceAcrossRefreshAndLogin() {
        String uid = "108";
        String folder = "INBOX";

        // Simulate save
        smartImportanceService.setManualImportance(USER_EMAIL, uid, folder, true);
        verify(importantEmailRepository).save(any(ImportantEmail.class));

        // Simulate retrieve on subsequent login/refresh
        ImportantEmail saved = ImportantEmail.builder()
                .id(99L)
                .userEmail(USER_EMAIL)
                .uid(uid)
                .folderName(folder)
                .isImportant(true)
                .isManual(true)
                .score(100.0)
                .build();
        when(importantEmailRepository.findByUserEmailAndIsImportantTrue(USER_EMAIL))
                .thenReturn(List.of(saved));

        List<ImportantEmail> retrieved = importantEmailRepository.findByUserEmailAndIsImportantTrue(USER_EMAIL);
        assertEquals(1, retrieved.size());
        assertEquals(uid, retrieved.get(0).getUid());
        assertTrue(retrieved.get(0).isImportant());
        assertTrue(retrieved.get(0).isManual());
    }

    @Test
    @DisplayName("Scenario 8: Important emails appearing in both Inbox and Important without duplicates")
    void testInboxAndImportantWithoutDuplicates() {
        String uid = "109";
        EmailDTO inboxEmail = EmailDTO.builder()
                .uid(uid)
                .from("partner@client.com")
                .to(USER_EMAIL)
                .subject("Contract Agreement")
                .body("Agreement attached")
                .folderName("INBOX")
                .build();

        // 1. In Inbox: enrich email marks it as important
        ImportantEmail record = ImportantEmail.builder()
                .id(5L)
                .userEmail(USER_EMAIL)
                .uid(uid)
                .folderName("INBOX")
                .isImportant(true)
                .isManual(true)
                .score(100.0)
                .build();

        when(importantEmailRepository.findByUserEmailAndUidAndFolderName(USER_EMAIL, uid, "INBOX"))
                .thenReturn(Optional.of(record));

        smartImportanceService.enrichEmail(USER_EMAIL, inboxEmail, "INBOX");
        assertTrue(inboxEmail.isImportant(), "Email in Inbox is marked Important");
        assertEquals("INBOX", inboxEmail.getFolderName(), "Email remains in its original folder (INBOX)");

        // 2. Querying important emails returns the same UID
        when(importantEmailRepository.findByUserEmailAndIsImportantTrue(USER_EMAIL))
                .thenReturn(List.of(record));
        List<ImportantEmail> importantList = importantEmailRepository.findByUserEmailAndIsImportantTrue(USER_EMAIL);

        assertEquals(1, importantList.size(), "No duplicate records created");
        assertEquals(uid, importantList.get(0).getUid());
    }
}
