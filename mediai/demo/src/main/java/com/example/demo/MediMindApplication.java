package com.example.demo;

import jakarta.persistence.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * MediMind AI backend: one file holding the entities, repositories and REST controllers.
 * SAFETY: it only stores and organizes data. It never diagnoses, prescribes or invents
 * missing prescription details. OCR and AI here are SIMULATED demo responses.
 */
@SpringBootApplication
@EnableJpaRepositories(considerNestedRepositories = true) // repositories below are nested classes
public class MediMindApplication {

    public static void main(String[] args) { SpringApplication.run(MediMindApplication.class, args); }

    // ===================== ENTITIES (public fields keep the file compact) =====================

    @Entity
    public static class Medicine {
        @Id @GeneratedValue public Long id;
        @NotBlank public String name;
        public String strength;      // may be null: never guessed
        public String instructions;  // may be null: never guessed
        @Pattern(regexp = "^\\d\\d:\\d\\d$", message = "reminderTime must be HH:mm")
        public String reminderTime;
        public LocalDate startDate, endDate;
        public boolean verified;     // true only after the user manually confirms
    }

    @Entity
    public static class Reminder {
        @Id @GeneratedValue public Long id;
        @NotBlank public String medicineName;
        @Pattern(regexp = "^\\d\\d:\\d\\d$", message = "time must be HH:mm")
        public String time;
        public String status = "pending"; // pending | taken | skipped
    }

    @Entity
    public static class HistoryEntry {
        @Id @GeneratedValue public Long id;
        @NotBlank public String doctor;
        public LocalDate uploadDate;
        public int medicineCount;
        public String status; // Verified | Completed | Needs review
    }

    @Entity
    public static class UserProfile {
        @Id public Long id = 1L;
        @NotBlank public String name;
        @Pattern(regexp = "^\\S+@\\S+\\.\\S+$", message = "invalid email")
        public String email;
        public boolean notifications = true;
    }

    /** The whole frontend state (medicines, reminders, history, settings) stored as one JSON row. */
    @Entity
    public static class AppState {
        @Id public Long id;
        @Lob @Column(columnDefinition = "LONGTEXT") public String payload;
    }

    /** A registered user. Only a BCrypt hash of the password is stored, never the password. */
    @Entity @Table(name = "app_user")
    public static class AppUser {
        @Id @GeneratedValue public Long id;
        public String name;
        @Column(unique = true) public String email;
        public String passwordHash;
        @Column(unique = true) public String token;
    }

    // ===================== REPOSITORIES =====================

    public interface MedicineRepo extends JpaRepository<Medicine, Long> {
        List<Medicine> findByNameContainingIgnoreCase(String q);
    }
    public interface ReminderRepo extends JpaRepository<Reminder, Long> { }
    public interface HistoryRepo extends JpaRepository<HistoryEntry, Long> { }
    public interface ProfileRepo extends JpaRepository<UserProfile, Long> { }
    public interface StateRepo extends JpaRepository<AppState, Long> { }
    public interface UserRepo extends JpaRepository<AppUser, Long> {
        Optional<AppUser> findByEmail(String email);
        Optional<AppUser> findByToken(String token);
    }

    // ===================== HELPERS =====================

    static ResponseStatusException bad(String msg) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg); }
    static ResponseStatusException notFound(String what) { return new ResponseStatusException(HttpStatus.NOT_FOUND, what + " not found"); }

    // ===================== CONTROLLERS =====================

    @RestController @RequestMapping("/api/medicines") @CrossOrigin
    public static class MedicineController {
        private final MedicineRepo meds; private final ReminderRepo rems;
        MedicineController(MedicineRepo meds, ReminderRepo rems) { this.meds = meds; this.rems = rems; }

        @GetMapping public List<Medicine> list(@RequestParam(required = false) String q) {
            return (q == null || q.isBlank()) ? meds.findAll() : meds.findByNameContainingIgnoreCase(q.trim());
        }
        @GetMapping("/{id}") public Medicine get(@PathVariable Long id) {
            return meds.findById(id).orElseThrow(() -> notFound("Medicine"));
        }
        @PostMapping @ResponseStatus(HttpStatus.CREATED)
        public Medicine create(@Valid @RequestBody Medicine m) {
            if (!m.verified) throw bad("Medicine must be manually confirmed (verified=true) before saving.");
            checkDates(m); m.id = null;
            Medicine saved = meds.save(m);
            if (saved.reminderTime != null) { // make it available to the reminder system
                Reminder r = new Reminder();
                r.medicineName = (saved.name + " " + Optional.ofNullable(saved.strength).orElse("")).trim();
                r.time = saved.reminderTime; rems.save(r);
            }
            return saved;
        }
        @PutMapping("/{id}") public Medicine update(@PathVariable Long id, @Valid @RequestBody Medicine in) {
            Medicine m = get(id); checkDates(in);
            m.name = in.name; m.strength = in.strength; m.instructions = in.instructions;
            m.reminderTime = in.reminderTime; m.startDate = in.startDate; m.endDate = in.endDate; m.verified = in.verified;
            return meds.save(m);
        }
        @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
        public void delete(@PathVariable Long id) { if (!meds.existsById(id)) throw notFound("Medicine"); meds.deleteById(id); }

        private void checkDates(Medicine m) {
            if (m.startDate != null && m.endDate != null && m.endDate.isBefore(m.startDate))
                throw bad("endDate must not be before startDate.");
        }
    }

    @RestController @RequestMapping("/api/reminders") @CrossOrigin
    public static class ReminderController {
        private final ReminderRepo rems;
        ReminderController(ReminderRepo rems) { this.rems = rems; }

        @GetMapping public List<Reminder> list() {
            return rems.findAll().stream().sorted(Comparator.comparing(r -> r.time)).collect(Collectors.toList());
        }
        @PostMapping @ResponseStatus(HttpStatus.CREATED)
        public Reminder create(@Valid @RequestBody Reminder r) { r.id = null; r.status = "pending"; return rems.save(r); }
        @PutMapping("/{id}") public Reminder update(@PathVariable Long id, @Valid @RequestBody Reminder in) {
            Reminder r = rems.findById(id).orElseThrow(() -> notFound("Reminder"));
            r.medicineName = in.medicineName; r.time = in.time; return rems.save(r);
        }
        /** Mark as taken / skipped: PATCH /api/reminders/5/status?value=taken */
        @PatchMapping("/{id}/status") public Reminder status(@PathVariable Long id, @RequestParam String value) {
            if (!List.of("pending", "taken", "skipped").contains(value)) throw bad("value must be pending, taken or skipped.");
            Reminder r = rems.findById(id).orElseThrow(() -> notFound("Reminder"));
            r.status = value; return rems.save(r);
        }
        @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
        public void delete(@PathVariable Long id) { if (!rems.existsById(id)) throw notFound("Reminder"); rems.deleteById(id); }
    }

    @RestController @RequestMapping("/api") @CrossOrigin
    public static class MiscController {
        private final HistoryRepo hist; private final MedicineRepo meds; private final ReminderRepo rems; private final ProfileRepo profiles;
        MiscController(HistoryRepo h, MedicineRepo m, ReminderRepo r, ProfileRepo p) { hist = h; meds = m; rems = r; profiles = p; }

        // ---- history ----
        @GetMapping("/history") public List<HistoryEntry> history(@RequestParam(required = false) String status) {
            return hist.findAll().stream().filter(h -> status == null || status.equals(h.status)).collect(Collectors.toList());
        }
        @DeleteMapping("/history/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
        public void deleteHistory(@PathVariable Long id) { if (!hist.existsById(id)) throw notFound("History entry"); hist.deleteById(id); }

        // ---- dashboard stats ----
        @GetMapping("/stats") public Map<String, Object> stats() {
            List<Reminder> all = rems.findAll();
            long taken = all.stream().filter(r -> "taken".equals(r.status)).count();
            long pending = all.stream().filter(r -> "pending".equals(r.status)).count();
            long handled = all.size() - pending;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("totalMedicines", meds.count()); m.put("upcomingReminders", pending);
            m.put("completedDoses", taken); m.put("adherencePercent", handled == 0 ? 100 : Math.round(taken * 100.0 / handled));
            return m;
        }

        // ---- profile ----
        @GetMapping("/profile") public UserProfile profile() { return profiles.findById(1L).orElseGet(UserProfile::new); }
        @PutMapping("/profile") public UserProfile saveProfile(@Valid @RequestBody UserProfile p) { p.id = 1L; return profiles.save(p); }

        // ---- SIMULATED scan: validates the upload, then returns fixed demo data. No real OCR/AI. ----
        @PostMapping(value = "/scan", consumes = "multipart/form-data")
        public Map<String, Object> scan(@RequestParam("file") MultipartFile file) {
            String type = Optional.ofNullable(file.getContentType()).orElse("");
            if (file.isEmpty()) throw bad("No file uploaded.");
            if (!List.of("image/jpeg", "image/png", "image/webp").contains(type)) throw bad("Only JPG, PNG or WEBP images are supported.");
            if (file.getSize() > 5L * 1024 * 1024) throw bad("File is larger than 5 MB.");
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("simulated", true);
            r.put("disclaimer", "Demonstration data. AI-generated explanations may contain errors. Always verify prescription details with your doctor or pharmacist.");
            r.put("extractedText", List.of("Dr. S. Patil - Rx", "1) Amoxicillin 500mg", "   1 cap TDS after food x 5 days", "2) Paracetamol [??]mg", "   SOS"));
            r.put("medicines", List.of(
                med("Amoxicillin", "500 mg", "1 capsule TDS after food for 5 days", 92, List.of()),
                med("Paracetamol", null, "SOS", 61, List.of("strength")))); // missing data stays null
            return r;
        }
        private Map<String, Object> med(String n, String s, String i, int conf, List<String> unclear) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", n); m.put("strength", s); m.put("instructions", i);
            m.put("confidenceSimulated", conf); m.put("unclearFields", unclear); m.put("needsManualReview", !unclear.isEmpty());
            return m;
        }

        // ---- scripted assistant: explains terminology only ----
        @PostMapping("/chat") public Map<String, String> chat(@RequestBody Map<String, String> body) {
            String t = Optional.ofNullable(body.get("message")).orElse("").toLowerCase();
            if (t.isBlank()) throw bad("message is required.");
            String reply;
            if (t.matches(".*(dose|dosage|diagnos|prescribe|increase|reduce|should i take).*"))
                reply = "I can't diagnose, recommend medicines, or advise dose changes. Please ask your doctor or pharmacist.";
            else if (t.contains("before food")) reply = "\"Before food\" usually means taking it before eating. If timing isn't written on your prescription, ask your pharmacist.";
            else if (t.contains("after food")) reply = "\"After food\" generally means after a meal. Check your prescription or ask your pharmacist for exact timing.";
            else if (t.matches(".*(abbrev|tds|bd|sos|od).*")) reply = "TDS = three times a day, BD = twice a day, OD = once a day, SOS = only if needed. Confirm anything unclear with your pharmacist.";
            else reply = "I can explain general prescription terms and the text on your prescription. For anything else, please ask your doctor or pharmacist.";
            return Map.of("reply", reply, "simulated", "true");
        }
    }

    /** Create account and sign in. */
    @RestController @RequestMapping("/api/auth") @CrossOrigin
    public static class AuthController {
        private final UserRepo users; private final BCryptPasswordEncoder enc = new BCryptPasswordEncoder();
        AuthController(UserRepo users) { this.users = users; }

        @PostMapping("/register") public Map<String, String> register(@RequestBody Map<String, String> b) {
            String name = clean(b.get("name")), email = clean(b.get("email")).toLowerCase(), pw = Optional.ofNullable(b.get("password")).orElse("");
            if (name.isEmpty()) throw bad("Name is required.");
            if (!email.matches("^\\S+@\\S+\\.\\S+$")) throw bad("Enter a valid email.");
            if (pw.length() < 8) throw bad("Password must be at least 8 characters.");
            if (users.findByEmail(email).isPresent())
                throw new ResponseStatusException(HttpStatus.CONFLICT, "An account with this email already exists. Sign in instead.");
            AppUser u = new AppUser(); u.name = name; u.email = email; u.passwordHash = enc.encode(pw);
            u.token = UUID.randomUUID().toString(); users.save(u);
            return session(u);
        }
        @PostMapping("/login") public Map<String, String> login(@RequestBody Map<String, String> b) {
            String email = clean(b.get("email")).toLowerCase(), pw = Optional.ofNullable(b.get("password")).orElse("");
            AppUser u = users.findByEmail(email).filter(x -> enc.matches(pw, x.passwordHash))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Wrong email or password."));
            u.token = UUID.randomUUID().toString(); users.save(u);
            return session(u);
        }
        static Map<String, String> session(AppUser u) { return Map.of("token", u.token, "name", u.name, "email", u.email); }
        static String clean(String s) { return s == null ? "" : s.trim(); }
    }

    /** Each signed-in user saves and loads their own data (needs header: Authorization: Bearer <token>). */
    @RestController @RequestMapping("/api/state") @CrossOrigin
    public static class StateController {
        private final StateRepo repo; private final UserRepo users;
        StateController(StateRepo repo, UserRepo users) { this.repo = repo; this.users = users; }

        private AppUser auth(String h) {
            if (h == null || !h.startsWith("Bearer ")) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please sign in.");
            return users.findByToken(h.substring(7)).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Session expired. Sign in again."));
        }
        @GetMapping(produces = "application/json")
        public ResponseEntity<String> load(@RequestHeader(value = "Authorization", required = false) String h) {
            AppUser u = auth(h);
            return repo.findById(u.id).map(s -> ResponseEntity.ok(s.payload))
                       .orElseGet(() -> ResponseEntity.noContent().build()); // 204 = nothing saved yet
        }
        @PutMapping(consumes = "application/json")
        public ResponseEntity<Void> save(@RequestBody String json, @RequestHeader(value = "Authorization", required = false) String h) {
            AppUser u = auth(h);
            if (json.length() > 2_000_000) throw bad("State is too large.");
            AppState s = new AppState(); s.id = u.id; s.payload = json; repo.save(s);
            return ResponseEntity.noContent().build();
        }
    }

    // ===================== ERROR HANDLING =====================

    @RestControllerAdvice
    public static class Errors {
        @ExceptionHandler(MethodArgumentNotValidException.class)
        public ResponseEntity<Map<String, Object>> invalid(MethodArgumentNotValidException e) {
            Map<String, String> fields = new LinkedHashMap<>();
            e.getBindingResult().getFieldErrors().forEach(f -> fields.put(f.getField(), f.getDefaultMessage()));
            return ResponseEntity.badRequest().body(Map.of("error", "Validation failed", "fields", fields));
        }
        @ExceptionHandler(ResponseStatusException.class)
        public ResponseEntity<Map<String, Object>> status(ResponseStatusException e) {
            return ResponseEntity.status(e.getStatusCode()).body(Map.of("error", Objects.requireNonNullElse(e.getReason(), "Error")));
        }
    }

    // ===================== SAMPLE DATA (first run only) =====================

    @Bean
    CommandLineRunner seed(MedicineRepo meds, ReminderRepo rems, HistoryRepo hist, ProfileRepo profiles) {
        return args -> {
            if (meds.count() > 0) return;
            Medicine m = new Medicine();
            m.name = "Metformin"; m.strength = "500 mg"; m.instructions = "1 tablet twice daily after meals";
            m.reminderTime = "08:00"; m.startDate = LocalDate.now().minusDays(10); m.endDate = LocalDate.now().plusDays(40); m.verified = true;
            meds.save(m);
            Reminder r = new Reminder(); r.medicineName = "Metformin 500 mg"; r.time = "08:00"; rems.save(r);
            HistoryEntry h = new HistoryEntry(); h.doctor = "Dr. S. Patil"; h.uploadDate = LocalDate.now().minusDays(10);
            h.medicineCount = 1; h.status = "Verified"; hist.save(h);
            UserProfile p = new UserProfile(); p.name = "Priya Kulkarni"; p.email = "priya@example.com"; profiles.save(p);
        };
    }
}