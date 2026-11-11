package com.ledgerflow.common.db;

/**
 * How one side of a money movement is named to the person looking at it.
 *
 * One rule, used by every list that shows a counterparty, so the same
 * account never reads differently on two screens:
 *
 *  - the platform cash account is "External bank" (deposits, withdrawals)
 *  - other platform accounts are "LedgerFlow" (fees)
 *  - a merchant account shows its business name
 *  - your own wallet shows the name you gave it
 *  - someone else's wallet shows the owner's name, never their label for it
 */
public final class PartyLabel {

    private PartyLabel() {
    }

    /**
     * SQL CASE expression for the rule above.
     *
     * @param account     alias of the accounts row
     * @param owner       alias of the LEFT JOINed users row owning it
     * @param viewerParam name of the bind parameter holding the viewer's user id
     */
    public static String sql(String account, String owner, String viewerParam) {
        return """
                CASE WHEN %1$s.id IS NULL THEN NULL
                     WHEN %1$s.type = 'SYSTEM_CASH' THEN 'External bank'
                     WHEN %1$s.type LIKE 'SYSTEM%%' THEN 'LedgerFlow'
                     WHEN %1$s.type = 'MERCHANT' THEN %1$s.name
                     WHEN %1$s.user_id = :%3$s THEN %1$s.name
                     ELSE %2$s.display_name END""".formatted(account, owner, viewerParam);
    }
}
