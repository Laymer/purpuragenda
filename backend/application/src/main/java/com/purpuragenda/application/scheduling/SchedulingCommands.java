package com.purpuragenda.application.scheduling;

import com.purpuragenda.application.port.out.SchedulingUnitOfWork;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.domain.DomainFailure;
import java.util.Objects;
import java.util.function.Function;

/** Shared internal command boundary. Callers must still authorize inside the transaction. */
public final class SchedulingCommands {
    private final SchedulingUnitOfWork unit;
    public SchedulingCommands(SchedulingUnitOfWork unit) { this.unit=Objects.requireNonNull(unit); }

    public Receipt execute(CommandKey key, Function<Transaction,Receipt> mutation) {
        Objects.requireNonNull(key); Objects.requireNonNull(mutation);
        return unit.execute(key, tx -> {
            ExpiryReclamation.reclaim(tx);
            return tx.attempt(() -> {
                try { return Objects.requireNonNull(mutation.apply(tx)); }
                catch(DomainFailure failure) {
                    Code code;
                    try { code=Code.valueOf(failure.code()); }
                    catch(IllegalArgumentException | NullPointerException unknown) { throw failure; }
                    if(!code.businessFailure()) throw failure;
                    return Receipt.failure(code);
                }
            });
        });
    }
}
