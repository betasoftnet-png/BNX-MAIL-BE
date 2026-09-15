package com.btctech.mailapp.strategy;

import com.btctech.mailapp.dto.RegisterRequest;
import com.btctech.mailapp.entity.AccountType;
import com.btctech.mailapp.entity.User;
import com.btctech.mailapp.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.Period;

@Service
@RequiredArgsConstructor
public class PersonalRegistrationStrategy implements RegistrationStrategy {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public User register(RegisterRequest request) {
        User user = new User();
        user.setUsername(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setFirstName(request.getFirstName());
        user.setLastName(request.getLastName());
        user.setPhoneNumber(request.getPhoneNumber());
        user.setDob(request.getDob());
        user.setActive(true);

        if (request.getDob() != null) {
            int age = Period.between(request.getDob(), LocalDate.now()).getYears();
            if (age < 18) {
                user.setAccountType(AccountType.CHILD);
                user.setRole("CHILD_USER");
                user.setApproved(true); // Frictionless MVP: Auto-approve for now
                // Link to parent if email provided
                if (request.getParentEmail() != null) {
                    userRepository.findByEmail(request.getParentEmail()).ifPresent(user::setParent);
                }
            } else {
                user.setAccountType(AccountType.PUBLIC);
                user.setRole("PUBLIC_USER");
                user.setApproved(true);
            }
        } else {
            // Default if no DOB provided (should ideally be required for Personal)
            user.setAccountType(AccountType.PUBLIC);
            user.setRole("PUBLIC_USER");
            user.setApproved(true);
        }

        return userRepository.save(user);
    }

    @Override
    public String getMode() {
        return "PERSONAL";
    }
}
