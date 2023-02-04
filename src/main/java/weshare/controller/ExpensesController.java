package weshare.controller;

import io.javalin.http.Handler;
import weshare.model.*;
import weshare.persistence.ExpenseDAO;
import weshare.persistence.PersonDAO;
import weshare.persistence.collectionbased.PersonDAOImpl;
import weshare.server.Routes;
import weshare.server.ServiceRegistry;
import weshare.server.WeShareServer;

import javax.money.MonetaryAmount;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static weshare.model.MoneyHelper.amountOf;

/**
 * Backend for all Expense based processing
 */
public class ExpensesController {

    /**
     * Collects and displays expenses for person logged in
     */
    public static final Handler expensesView = context -> {
        ExpenseDAO expensesDAO = ServiceRegistry.lookup(ExpenseDAO.class);
        Person personLoggedIn = WeShareServer.getPersonLoggedIn(context);

        Collection<Expense> expenses = expensesDAO.findExpensesForPerson(personLoggedIn);

        MonetaryAmount grandTotal = MoneyHelper.amountOf(0);
        for (Expense expense : expenses) {
            grandTotal = grandTotal.add(expense.amountLessPaymentsReceived());
        }

        Map<String, Object> viewModel = Map.of(
                "expenses", expenses,
                "grandTotal", grandTotal,
                "zeroTotal", amountOf(0));
        context.render("expenses.html", viewModel);
    };

    /**
     * Renders a new expense form
     */
    public static final Handler newExpenseForm = context -> {
        context.render("newexpense.html");
    };

    /**
     * Processing for new expense submitted from form
     */
    public static final Handler newExpenseSubmit = context -> {
        ExpenseDAO expensesDAO = ServiceRegistry.lookup(ExpenseDAO.class);
        Person personLoggedIn = WeShareServer.getPersonLoggedIn(context);

        String description = context.formParamAsClass("description", String.class)
                .check(Objects::nonNull, "Description is required")
                .get();

        LocalDate date = LocalDate.parse((context.formParamAsClass("date", String.class)
                .check(Objects::nonNull, "Date is required")
                .get()), DateHelper.DD_MM_YYYY);

        Long amount = context.formParamAsClass("amount", Long.class)
                .check(Objects::nonNull, "Amount is required")
                .get();

        Expense newExpense = new Expense(personLoggedIn, description, amountOf(amount), date);
        expensesDAO.save(newExpense);

        context.redirect(Routes.EXPENSES);
    };

    /**
     * Collects and displays payment requests sent by person logged in
     */
    public static final Handler paymentRequestsSent = context -> {
        ExpenseDAO expensesDAO = ServiceRegistry.lookup(ExpenseDAO.class);
        Person personLoggedIn = WeShareServer.getPersonLoggedIn(context);
        Collection<PaymentRequest> paymentRequestsList = expensesDAO.findPaymentRequestsSent(personLoggedIn);
        MonetaryAmount grandTotal = amountOf(0);

        Predicate<PaymentRequest> byUnpaid = PaymentRequest -> !PaymentRequest.isPaid();

        Collection<PaymentRequest> unpaidRequests = paymentRequestsList.stream().filter(byUnpaid).collect(Collectors.toList());

        for (PaymentRequest paymentRequest : paymentRequestsList) {
            if (!paymentRequest.isPaid()) {
                grandTotal = grandTotal.add(paymentRequest.getAmountToPay());
            }
        }

        Map<String, Object> viewModel = Map.of(
                "paymentRequests", unpaidRequests,
                "grandTotal", grandTotal);

        context.render("paymentrequests_sent.html", viewModel);
    };

    /**
     * Collects and displays payment requests received for person logged in
     */
    public static final Handler paymentRequestsReceived = context -> {
        ExpenseDAO expensesDAO = ServiceRegistry.lookup(ExpenseDAO.class);
        Person personLoggedIn = WeShareServer.getPersonLoggedIn(context);
        Collection<PaymentRequest> requestsReceivedList = expensesDAO.findPaymentRequestsReceived(personLoggedIn);

        MonetaryAmount grandTotal = amountOf(0);
        for (PaymentRequest request : requestsReceivedList) {
            if (!request.isPaid()) {
                grandTotal = grandTotal.add(request.getAmountToPay());
            }
        }

        Map<String, Object> viewModel = Map.of(
                "requestsReceived", requestsReceivedList,
                "grandTotal", grandTotal);
        context.render("paymentrequests_received.html", viewModel);
    };

    /**
     * Renders a new payment request form selected expe
     */
    public static final Handler newPaymentRequest = context ->{
        ExpenseDAO expensesDAO = ServiceRegistry.lookup(ExpenseDAO.class);
        Person personLoggedIn = WeShareServer.getPersonLoggedIn(context);
        UUID expenseID = UUID.fromString(Objects.requireNonNull(context.queryParam("expenseId")));
        Optional<Expense> requestedExpense = expensesDAO.get(expenseID);

        Map<String, Object> viewModel = Map.of(
                "expense", requestedExpense.get());
        context.render("paymentrequest.html", viewModel);
    };

    /**
     * The constant newPaymentRequestSubmit.
     */
    public static final Handler newPaymentRequestSubmit = context -> {
        ExpenseDAO expensesDAO = ServiceRegistry.lookup(ExpenseDAO.class);

        UUID expenseID = UUID.fromString(context.formParamAsClass("expense_id", String.class).get());

        String email = context.formParamAsClass("email", String.class)
                .check(Objects::nonNull, "Email is required")
                .get();

        Long amountToPay = context.formParamAsClass("amount", Long.class)
                .check(Objects::nonNull, "Amount is required")
                .get();

        LocalDate dueDate = LocalDate.parse((context.formParamAsClass("date", String.class)
                .check(Objects::nonNull, "Date is required")
                .get()), DateHelper.DD_MM_YYYY);

        Expense expense = expensesDAO.get(expenseID).get();
        PersonDAO personDAO = new PersonDAOImpl();
        Person personWhoShouldPayBack = null;
        try {
            personWhoShouldPayBack = personDAO.findPersonByEmail(email).get();
        } catch (Exception e) {
            personWhoShouldPayBack = new Person(email);
            personDAO.savePerson(personWhoShouldPayBack);
        }

        expense.requestPayment(personWhoShouldPayBack, amountOf(amountToPay), dueDate);

        context.redirect(Routes.NEW_PAYMENT_REQUEST+"?expenseId="+expenseID);
    };

    /**
     * The constant processPayment.
     */
    public static final Handler processPayment = context -> {
        ExpenseDAO expensesDAO = ServiceRegistry.lookup(ExpenseDAO.class);
        Person personLoggedIn = WeShareServer.getPersonLoggedIn(context);

        UUID requestID = UUID.fromString(context.formParamAsClass("request_id", String.class).get());

        Collection<PaymentRequest> paymentRequestsReceived = expensesDAO.findPaymentRequestsReceived(personLoggedIn);

        for (PaymentRequest requestReceived : paymentRequestsReceived) {
            if (requestReceived.getId().equals(requestID)) {
                requestReceived.pay(personLoggedIn, DateHelper.TODAY);
                Expense newExpense = new Expense(personLoggedIn, requestReceived.getDescription(), requestReceived.getAmountToPay(), DateHelper.TODAY);
                expensesDAO.save(newExpense);
                break;
            }
        }

        context.redirect(Routes.PAYMENTS_RECEIVED);
    };
}
