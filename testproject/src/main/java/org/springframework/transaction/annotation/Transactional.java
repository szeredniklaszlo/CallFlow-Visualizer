package org.springframework.transaction.annotation;

import java.lang.annotation.*;

@Target({ ElementType.TYPE, ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
public @interface Transactional {
    Propagation propagation() default Propagation.REQUIRED;

    boolean readOnly() default false;
}
