package local.tv.home;

public final class LiveRestartBudgetTest {

    private static int checks;

    private static void check(boolean value) {
        checks++;
        if (!value) throw new AssertionError("Reconnect " + checks);
    }

    public static void main(String[] args) {
        LiveRestartBudget budget = new LiveRestartBudget();
        check(budget.acquire());
        check(budget.acquire());
        check(!budget.acquire());
        for (int i = 0; i < 40; i++) budget.observe(i * 1000L, 10, true);
        check(!budget.acquire());
        for (int i = 0; i < 32; i++) budget.observe(i * 1000L, 20 + i, true);
        check(budget.acquire());
        budget.observe(40000, 100, false);
        check(budget.acquire());
        check(!budget.acquire());
        for (int i = 0; i < 40; i++) budget.observe(i * 1000L, 200 + i, false);
        check(!budget.acquire());
        budget.reset();
        check(budget.acquire());
        check(budget.attempts() == 1);
        System.out.println(checks + " live reconnect checks passed");
    }
}
