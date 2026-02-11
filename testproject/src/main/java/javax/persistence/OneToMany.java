package javax.persistence;

import java.lang.annotation.*;

@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OneToMany {
    FetchType fetch() default FetchType.LAZY;

    CascadeType[] cascade() default {};

    boolean orphanRemoval() default false;
}
