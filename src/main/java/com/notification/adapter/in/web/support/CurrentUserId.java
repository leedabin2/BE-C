package com.notification.adapter.in.web.support;
// PRD: F1-2, F1-3 (소유권) → docs/prd/F1.md

import java.lang.annotation.*;

@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CurrentUserId {}
