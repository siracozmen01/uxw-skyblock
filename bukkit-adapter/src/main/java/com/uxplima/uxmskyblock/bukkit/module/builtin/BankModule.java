package com.uxplima.uxmskyblock.bukkit.module.builtin;

import java.util.List;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.application.bank.IslandBankService;
import com.uxplima.uxmskyblock.core.application.module.AbstractFeatureModule;
import com.uxplima.uxmskyblock.core.application.module.ModuleContext;
import com.uxplima.uxmskyblock.core.domain.module.ModuleDescriptor;

/**
 * Built-in bank module providing island multi-currency treasury and transactional accounting.
 */
public final class BankModule extends AbstractFeatureModule {

    private final IslandBankService bankService;

    public BankModule(IslandBankService bankService) {
        super(new ModuleDescriptor(
                "bank", "1.0.0", List.of("core >= 1.0.0"), List.of(), List.of("island-bank"), ">=1.0.0", false));
        this.bankService = Objects.requireNonNull(bankService, "bankService must not be null");
    }

    @Override
    protected void onEnable(ModuleContext context) {
        context.registerService(IslandBankService.class, bankService);
    }
}
