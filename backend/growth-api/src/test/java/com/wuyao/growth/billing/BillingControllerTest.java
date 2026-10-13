package com.wuyao.growth.billing;

import com.wuyao.growth.common.security.AuthPrincipal;
import com.wuyao.growth.iam.entity.User;
import com.wuyao.growth.iam.repository.UserRepository;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.mockito.Mockito.*;

class BillingControllerTest {
    @Test void automaticPollingReadsLocalStateAndOnlyExplicitRefreshReconciles() {
        var service=mock(BillingService.class);
        var checkout=mock(BillingCheckoutService.class);
        var users=mock(UserRepository.class);
        var owner=new User(); owner.setTenantId(7L); owner.setRole("OWNER");
        when(users.findById(1L)).thenReturn(Optional.of(owner));
        var controller=new BillingController(service,new BillingProperties(),checkout,users);
        var principal=new AuthPrincipal(1L,7L,"",0);
        controller.order("test-order",principal);
        verify(service).order("test-order");
        verifyNoInteractions(checkout);
        controller.refresh("test-order",principal);
        verify(checkout).refresh("test-order");
    }
}
