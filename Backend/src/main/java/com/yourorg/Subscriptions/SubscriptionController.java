package com.yourorg.Subscriptions;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.yourorg.Users.CurrentUserService;
import com.yourorg.Users.User;

/**
 * CRUD for the caller's subscriptions.
 *
 * <p>New. The README documented {@code GET/POST /api/subscriptions}, but no
 * controller mapped those paths -- the endpoints returned 404, and the only way to
 * get a subscription into the system was an INSERT by hand. Nothing else in the
 * product works without this: with no subscriptions there is no spend to analyse and
 * nothing for incoming usage to match against.
 *
 * <p>Every operation is scoped to the authenticated user; the account is never taken
 * from the request.
 */
@RestController
@RequestMapping("/api/subscriptions")
public class SubscriptionController {

    @Autowired
    private SubscriptionService subscriptionService;

    @Autowired
    private CurrentUserService currentUser;

    @GetMapping
    public List<SubscriptionResponseDTO> list(
            @RequestParam(name = "activeOnly", defaultValue = "false") boolean activeOnly) {
        Long userId = currentUser.requireId();
        List<Subscription> subs = activeOnly
                ? subscriptionService.findActiveByUser(userId)
                : subscriptionService.findByUser(userId);
        return subs.stream().map(SubscriptionResponseDTO::from).toList();
    }

    @GetMapping("/{id}")
    public SubscriptionResponseDTO get(@PathVariable Long id) {
        return SubscriptionResponseDTO.from(
                subscriptionService.requireOwned(currentUser.requireId(), id));
    }

    @PostMapping
    public ResponseEntity<SubscriptionResponseDTO> create(@RequestBody SubscriptionRequestDTO request) {
        User owner = currentUser.require();
        Subscription created = subscriptionService.create(owner, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(SubscriptionResponseDTO.from(created));
    }

    @PutMapping("/{id}")
    public SubscriptionResponseDTO update(@PathVariable Long id, @RequestBody SubscriptionRequestDTO request) {
        return SubscriptionResponseDTO.from(
                subscriptionService.update(currentUser.requireId(), id, request));
    }

    /**
     * Cancels rather than deletes: the usage and billing history behind the
     * subscription is the user's own record of what they spent.
     */
    @DeleteMapping("/{id}")
    public SubscriptionResponseDTO cancel(@PathVariable Long id) {
        return SubscriptionResponseDTO.from(
                subscriptionService.cancel(currentUser.requireId(), id));
    }
}
