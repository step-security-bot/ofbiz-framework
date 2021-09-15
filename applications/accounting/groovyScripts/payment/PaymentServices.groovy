/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
import org.apache.ofbiz.accounting.invoice.InvoiceWorker
import org.apache.ofbiz.accounting.payment.PaymentWorker
import org.apache.ofbiz.base.util.UtilDateTime
import org.apache.ofbiz.base.util.UtilFormatOut
import org.apache.ofbiz.base.util.UtilMisc
import org.apache.ofbiz.base.util.UtilProperties
import org.apache.ofbiz.entity.condition.EntityCondition
import org.apache.ofbiz.entity.condition.EntityConditionBuilder
import org.apache.ofbiz.entity.condition.EntityOperator
import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.service.ServiceUtil

import java.sql.Timestamp

def createPayment() {
    if (!security.hasEntityPermission("ACCOUNTING", "_CREATE", parameters.userLogin) &&
            (!security.hasEntityPermission("PAY_INFO", "_CREATE", parameters.userLogin) &&
                    userLogin.partyId != parameters.partyIdFrom && userLogin.partyId != parameters.partyIdTo)) {
        return error(label("AccountingUiLabels", "AccountingCreatePaymentPermissionError"))
    }

    GenericValue payment = makeValue("Payment")
    payment.paymentId = parameters.paymentId ?: delegator.getNextSeqId("Payment")
    parameters.statusId = parameters.statusId ?: "PMNT_NOT_PAID"

    if (parameters.paymentMethodId) {
        GenericValue paymentMethod = from("PaymentMethod").where("paymentMethodId", parameters.paymentMethodId).queryOne()
        if (parameters.paymentMethodTypeId != paymentMethod.paymentMethodTypeId) {
            logInfo("Replacing passed payment method type [" + parameters.paymentMethodTypeId + "] with payment method type [" + paymentMethod.paymentMethodTypeId + "] for payment method [" + parameters.paymentMethodId +"]")
            parameters.paymentMethodTypeId = paymentMethod.paymentMethodTypeId
        }
    }

    if (parameters.paymentPreferenceId) {
        GenericValue orderPaymentPreference = from("OrderPaymentPreference").where("orderPaymentPreferenceId", parameters.paymentPreferenceId).queryOne()
        parameters.paymentId = parameters.paymentId ?: orderPaymentPreference.paymentMethodId
        parameters.paymentMethodTypeId = parameters.paymentMethodTypeId ?: orderPaymentPreference.paymentMethodTypeId
    }

    if (!parameters.paymentMethodTypeId) {
        return error(label("AccountingUiLabels", "AccountingPaymentMethodIdPaymentMethodTypeIdNullError"))
    }

    payment.setNonPKFields(parameters)
    payment.effectiveDate = payment.effectiveDate ?: UtilDateTime.nowTimestamp()
    payment.create()
    return success(paymentId: payment.paymentId)
}

def getInvoicePaymentInfoList() {
    // Create a list with information on payment due dates and amounts for the invoice
    GenericValue invoice = parameters.invoice ?: from("Invoice").where("invoiceId", parameters.invoiceId).queryOne()
    List invoicePaymentInfoList = []

    BigDecimal invoiceTotalAmount = InvoiceWorker.getInvoiceTotal(invoice)
    BigDecimal invoiceTotalAmountPaid = InvoiceWorker.getInvoiceApplied(invoice)

    List invoiceTerms = from("InvoiceTerm")
            .where("invoiceId", invoice.invoiceId)
            .queryList()

    BigDecimal remainingAppliedAmount = invoiceTotalAmountPaid
    BigDecimal computedTotalAmount = (BigDecimal) 0

    for (invoiceTerm in invoiceTerms) {
        GenericValue termType = from("TermType").where("termTypeId", invoiceTerm.termTypeId).cache().queryOne()
        if ("FIN_PAYMENT_TERM" == termType.parentTypeId) {
            Map invoicePaymentInfo = [invoiceId    : invoice.invoiceId,
                                      invoiceTermId: invoiceTerm.invoiceTermId,
                                      termTypeId   : invoiceTerm.termTypeId,
                                      dueDate      : UtilDateTime.getDayEnd(invoice.invoiceDate, invoiceTerm.termDays)]

            BigDecimal invoiceTermAmount = (invoiceTerm.termValue * invoiceTotalAmount ) / 100
            invoicePaymentInfo.amount = invoiceTermAmount
            computedTotalAmount += invoicePaymentInfo.amount as BigDecimal

            if (remainingAppliedAmount >= invoiceTermAmount) {
                invoicePaymentInfo.paidAmount = invoiceTermAmount
                remainingAppliedAmount -= invoiceTermAmount
            } else {
                invoicePaymentInfo.paidAmount = remainingAppliedAmount
                remainingAppliedAmount = (BigDecimal) 0
            }
            invoicePaymentInfo.outstandingAmount = invoicePaymentInfo.amount - invoicePaymentInfo.paidAmount
            invoicePaymentInfoList << invoicePaymentInfo
        }
    }

    if (remainingAppliedAmount > 0.0 || invoiceTotalAmount <= 0.0 || computedTotalAmount < invoiceTotalAmount) {
        Map invoicePaymentInfo = [invoiceId: invoice.invoiceId,
                                  amount: invoiceTotalAmount - computedTotalAmount,
                                  paidAmount: remainingAppliedAmount]
        invoicePaymentInfo.outstandingAmount = invoicePaymentInfo.amount - invoicePaymentInfo.paidAmount
        GenericValue invoiceTerm = from("InvoiceTerm")
                .where("invoiceId", invoice.invoiceId,
                        "termTypeId", "FIN_PAYMENT_TERM")
                .queryFirst()
        if (invoiceTerm) {
            invoicePaymentInfo.termTypeId = invoiceTerm.termTypeId
            invoicePaymentInfo.dueDate = UtilDateTime.getDayEnd(invoice.invoiceDate, invoiceTerm.termDays)
        } else {
            invoicePaymentInfo.dueDate = UtilDateTime.getDayEnd(invoice.invoiceDate)
        }
        invoicePaymentInfoList << invoicePaymentInfo
    }
    return success(invoicePaymentInfoList: invoicePaymentInfoList)
}

def updatePayment() {
    GenericValue payment = from("Payment").where(parameters).queryOne()
    if (!security.hasEntityPermission("ACCOUNTING", "_UPDATE", parameters.userLogin) &&
        (!security.hasEntityPermission("PAY_INFO", "_UPDATE", parameters.userLogin) &&
        userLogin.partyId != payment.partyIdFrom && userLogin.partyId != payment.partyIdTo)) {
        return error(label("AccountingUiLabels", "AccountingUpdatePaymentPermissionError"))
    }
    if ("PMNT_NOT_PAID" != payment.statusId) {
        // check if only status change
        GenericValue oldPayment = makeValue("Payment", payment)
        GenericValue newPayment = makeValue("Payment", payment)
        newPayment.setNonPKFields(parameters)

        // fields :- comments, paymentRefNum, finAccountTransId, statusIhStatus does not allow an update of the information are editable for Payment
        oldPayment.statusId = newPayment.statusId
        oldPayment.comments = newPayment.comments
        oldPayment.paymentRefNum = newPayment.paymentRefNum ?: null
        oldPayment.finAccountTransId = newPayment.finAccountTransId ?: null
        if (!oldPayment.equals(newPayment)) {
            return error(label("AccountingUiLabels", "AccountingPSUpdateNotAllowedBecauseOfStatus"))
        }
    }
    String statusIdSave = payment.statusId  // do not allow status change here
    payment.setNonPKFields(parameters)
    payment.statusId = statusIdSave  // do not allow status change here
    payment.effectiveDate = payment.effectiveDate ?: UtilDateTime.nowTimestamp()
    if (payment.paymentMethodId) {
        GenericValue paymentMethod = from("PaymentMethod").where("paymentMethodId", payment.paymentMethodId).queryOne()
        if (payment.paymentMethodTypeId != paymentMethod.paymentMethodTypeId) {
            logInfo("Replacing passed payment method type [" + parameters.paymentMethodTypeId + "] with payment method type [" +
                paymentMethod.paymentMethodTypeId + "] for payment method [" + parameters.paymentMethodId +"]")
        }
        payment.paymentMethodTypeId = paymentMethod.paymentMethodTypeId
    }
    payment.store()
    if (parameters.statusId &&
            parameters.statusId != statusIdSave) {
        Map serviceResult = run service: 'setPaymentStatus', with: [*        : parameters,
                                                                    paymentId: payment.paymentId]
        if (!ServiceUtil.isSuccess(serviceResult)) return serviceResult
    }
    return success()
}

def createPaymentAndApplicationForParty() {
    BigDecimal paymentAmount = 0
    List invoiceIds = []
    String paymentId
    parameters.invoices.each { GenericValue invoice ->
        if ("INVOICE_READY" == invoice.statusId) {
            Map serviceResult = run service: 'getInvoicePaymentInfoList', with: invoice.getAllFields()
            if (ServiceUtil.isError(serviceResult)) return serviceResult
            paymentAmount += serviceResult.invoicePaymentInfoList[0].outstandingAmount
        } else {
            return error(label("AccountingUiLabels", "AccountingInvoicesRequiredInReadyStatus"))
        }
    }
    if (paymentAmount > 0) {
        Map serviceResult = run service: 'getPartyAccountingPreferences', with: parameters
        if (ServiceUtil.isError(serviceResult)) return serviceResult
        serviceResult = run service: 'createPayment', with: [paymentTypeId      : "VENDOR_PAYMENT",
                                                             partyIdFrom        : parameters.organizationPartyId,
                                                             currencyUomId      : serviceResult.partyAccountingPreference.baseCurrencyUomId,
                                                             partyIdTo          : parameters.partyId,
                                                             statusId           : "PMNT_SENT",
                                                             amount             : paymentAmount,
                                                             paymentMethodTypeId: parameters.paymentMethodTypeId,
                                                             paymentMethodId    : parameters.paymentMethodId,
                                                             paymentRefNum      : parameters.checkStartNumber]
        if (ServiceUtil.isError(serviceResult)) return serviceResult
        paymentId = serviceResult.paymentId

        parameters.invoices.each { GenericValue invoice ->
            if ("INVOICE_READY" == invoice.statusId) {
                serviceResult = run service: 'getInvoicePaymentInfoList', with: invoice.getAllFields()
                if (ServiceUtil.isError(serviceResult)) return serviceResult
                Map invoicePaymentInfo = serviceResult.invoicePaymentInfoList[0]
                if (invoicePaymentInfo.outstandingAmount > 0) {
                    serviceResult = run service: 'createPaymentApplication', with: [paymentId    : paymentId,
                                                                                    amountApplied: invoicePaymentInfo.outstandingAmount,
                                                                                    invoiceId    : invoice.invoiceId]
                    if (ServiceUtil.isError(serviceResult)) return serviceResult
                }
            }
            invoiceIds << invoice.invoiceId
        }
    }
    return success([paymentId : paymentId,
                    invoiceIds: invoiceIds,
                    amount    : paymentAmount])
}

def getPaymentRunningTotal(){
    String currencyUomId
    List paymentIds = parameters.paymentIds
    BigDecimal runningTotal = 0
    from("Payment")
            .where(EntityCondition.makeCondition("paymentId", EntityOperator.IN, paymentIds))
            .queryList()
            .each {
                runningTotal += it.amount
            }

    if (parameters.organizationPartyId) {
        Map serviceResult = run service: 'getPartyAccountingPreferences', with: [organizationPartyId: parameters.organizationPartyId]
        GenericValue partyAcctgPreference = serviceResult.partyAccountingPreference
        currencyUomId = partyAcctgPreference.baseCurrencyUomId ?: UtilProperties.getPropertyValue('general.properties', 'currency.uom.id.default')
    } else  {
        currencyUomId = UtilProperties.getPropertyValue('general.properties', 'currency.uom.id.default')
    }
    return success(paymentRunningTotal: UtilFormatOut.formatCurrency(runningTotal, currencyUomId, locale))
}

def createPaymentContent() {
    GenericValue newEntity = makeValue("PaymentContent", parameters)
    newEntity.fromDate = newEntity.fromDate ?: UtilDateTime.nowTimestamp()
    newEntity.create()

    Map result = run service: 'updateContent', with: parameters
    if (ServiceUtil.isError(result)) return result

    return success([contentId           : newEntity.contentId,
                    paymentId           : newEntity.paymentId,
                    paymentContentTypeId: newEntity.paymentContentTypeId])
}

//TODO: This can be converted into entity-auto with a seca rule for updateContent
def updatePaymentContent() {
    GenericValue lookedUpValue = from("PaymentContent").where(parameters).queryOne()
    if (lookedUpValue) {
        lookedUpValue.setNonPKFields(parameters)
        lookedUpValue.store()
        Map result = run service: 'updateContent', with: parameters
        if (ServiceUtil.isError(result)) return result
        return success()
    }
    return error("Error getting Payment Content")
}

def massChangePaymentStatus() {
    parameters.paymentIds.each{ paymentId ->
        Map result = run service: 'setPaymentStatus', with: [paymentId: paymentId,
                                                             statusId : parameters.statusId]
        if (ServiceUtil.isError(result)) return result
    }
    return success()
}

def getInvoicePaymentInfoListByDueDateOffset() {

    List filteredInvoicePaymentInfoList = []
    Timestamp asOfDate = UtilDateTime.getDayEnd(UtilDateTime.nowTimestamp(), (long) parameters.daysOffset)
    EntityCondition condition = new EntityConditionBuilder().AND() {
        EQUALS(invoiceTypeId: parameters.invoiceTypeId)
        NOT_IN(statusId: ["INVOICE_CANCELLED", "INVOICE_PAID"])
    }
    if (parameters.partyId) {
        condition = new EntityConditionBuilder().AND(condition) {
            EQUALS(partyId: parameters.partyId)
        }
    }
    if (parameters.partyIdFrom) {
        condition = new EntityConditionBuilder().AND(condition) {
            EQUALS(partyIdFrom: parameters.partyIdFrom)
        }
    }

    from("Invoice")
            .where(condition)
            .orderBy("invoiceDate")
            .queryList()
            .each {
                Map serviceResult = run service: 'getInvoicePaymentInfoList', with: [invoice: it]
                if (ServiceUtil.isError(serviceResult)) return serviceResult
                invoicePaymentInfoList = serviceResult.invoicePaymentInfoList
                if (invoicePaymentInfoList) {
                    invoicePaymentInfoList.each { Map invoicePaymentInfo ->
                        if (invoicePaymentInfo.outstandingAmount.compareTo(BigDecimal.ZERO) > 0 && invoicePaymentInfo.dueDate.before(asOfDate)) {
                            filteredInvoicePaymentInfoList << invoicePaymentInfo
                        }
                    }
                }
            }

    return success(invoicePaymentInfoList: filteredInvoicePaymentInfoList)
}

def getPaymentGroupReconciliationId() {
    GenericValue paymentGroupMember = from("PaymentGroupMember")
            .where("paymentGroupId", parameters.paymentGroupId)
            .queryFirst()
    String glReconciliationId = null
    if (paymentGroupMember) {
        GenericValue payment = paymentGroupMember.getRelatedOne('Payment', false)
        GenericValue finAccountTrans = payment.getRelatedOne('FinAccountTrans', false)
        if (finAccountTrans) {
            glReconciliationId = finAccountTrans.glReconciliationId
        }
    }
    return success(glReconciliationId: glReconciliationId)
}

def createPaymentAndApplication() {
    Map createPaymentResp = run service: 'createPayment', with: parameters
    if (ServiceUtil.isError(createPaymentResp)) return createPaymentResp

    Map createPaymentApplicationResp = run service: 'createPaymentApplication', with: [*            : parameters,
                                                                                       paymentId    : createPaymentResp.paymentId,
                                                                                       amountApplied: parameters.amount]
    if (ServiceUtil.isError(createPaymentApplicationResp)) return createPaymentApplicationResp

    return success([paymentId           : createPaymentResp.paymentId,
                    paymentApplicationId: createPaymentApplicationResp.paymentApplicationId])
}

def createFinAccoutnTransFromPayment() {
    Map result = run service: 'createFinAccountTrans', with: [*                    : parameters,
                                                              finAccountTransTypeId: 'WITHDRAWAL',
                                                              partyId              : parameters.organizationPartyId,
                                                              transactionDate      : UtilDateTime.nowTimestamp(),
                                                              entryDate            : UtilDateTime.nowTimestamp(),
                                                              statusId             : 'FINACT_TRNS_CREATED',
                                                              comments             : "Pay to ${parameters.partyId} for invoice Ids - ${parameters.invoiceIds}"]

    if (ServiceUtil.isError(result)) {
        return result
    }
    String finAccountTransId = result.finAccountTransId
    result = run service: 'updatePayment', with: [finAccountTransId: finAccountTransId,
                                                  paymentId        : parameters.paymentId]
    if (ServiceUtil.isError(result)) {
        return result
    }
    return success(finAccountTransId: finAccountTransId)
}

def quickSendPayment() {
    Map updatePaymentResp = run service: 'updatePayment', with: parameters
    if (ServiceUtil.isError(updatePaymentResp)) return updatePaymentResp

    Map setPaymentStatusResp = run service: 'setPaymentStatus', with: [*       : parameters,
                                                                       statusId: "PMNT_SENT"]
    if (ServiceUtil.isError(setPaymentStatusResp)) return setPaymentStatusResp

    return success()
}

/**
 * Service to cancel payment batch
 */
def cancelPaymentBatch() {
    List<GenericValue> paymentGroupMemberAndTransList = from("PmtGrpMembrPaymentAndFinAcctTrans")
            .where("paymentGroupId", parameters.paymentGroupId)
            .queryList()

    if (paymentGroupMemberAndTransList) {
        if ("FINACT_TRNS_APPROVED" == paymentGroupMemberAndTransList[0].finAccountTransStatusId) {
            return error(label('AccountingErrorUiLabels', 'AccountingTransactionIsAlreadyReconciled'))
        }

        for (GenericValue paymentGroupMember : paymentGroupMemberAndTransList) {
            Map result = run service: "expirePaymentGroupMember", with: paymentGroupMember.getAllFields()
            if (ServiceUtil.isError(result)) return result

            GenericValue finAccountTrans = from("FinAccountTrans").where("finAccountTransId", paymentGroupMember.finAccountTransId).queryOne()
            if (finAccountTrans) {
                finAccountTrans.statusId = "FINACT_TRNS_CANCELED"
                result = run service: "setFinAccountTransStatus", with: [*       : finAccountTrans.getAllFields(),
                                                                         statusId: 'FINACT_TRNS_CANCELED']
                if (ServiceUtil.isError(result)) return result
            }
        }
    }
    return success()
}

def getPayments() {
    List payments = []
    if (parameters.paymentGroupId) {
        List paymentIds = from("PaymentGroupMember")
                .where("paymentGroupId", parameters.paymentGroupId)
                .filterByDate()
                .distinct()
                .getFieldList("paymentId")
        if (paymentIds) {
            payments = from("Payment")
                    .where(EntityCondition.makeCondition("paymentId", EntityOperator.IN, paymentIds))
                    .queryList()
        }
    }
    if (parameters.finAccountTransId) {
        payments = from("Payment")
                .where("finAccountTransId", parameters.finAccountTransId)
                .queryList()
    }
    return success(payments: payments)
}

def cancelCheckRunPayments() {
    List paymentGroupMemberAndTransList = from("PmtGrpMembrPaymentAndFinAcctTrans")
            .where("paymentGroupId", parameters.paymentGroupId)
            .queryList()
    if (paymentGroupMemberAndTransList) {
        if ("FINACT_TRNS_APPROVED" != paymentGroupMemberAndTransList[0].finAccountTransStatusId) {
            for (GenericValue paymentGroupMemberAndTrans : paymentGroupMemberAndTransList) {
                GenericValue payment = from("Payment").where("paymentId", paymentGroupMemberAndTrans.paymentId).queryOne()
                Map result = run service: "voidPayment", with: payment.getAllFields()
                if (ServiceUtil.isError(result)) return result
                result = run service: "expirePaymentGroupMember", with: paymentGroupMemberAndTrans.getAllFields()
                if (ServiceUtil.isError(result)) return result
            }
        } else {
            return error(label("AccountingErrorUiLabels", "AccountingCheckIsAlreadyIssued"))
        }
    }
    return success()
}

def createPaymentAndPaymentGroupForInvoices() {
    Map result
    GenericValue paymentMethod = from("PaymentMethod").where("paymentMethodId", parameters.paymentMethodId).queryOne()

    if (paymentMethod) {
        GenericValue finAccount = from("FinAccount").where("finAccountId", paymentMethod.finAccountId).queryOne()
        if (finAccount.statusId == "FNACT_MANFROZEN") {
            return error(label('AccountingErrorUiLabels', 'AccountingFinAccountInactiveStatusError'))
        } else if (finAccount.statusId == "FNACT_CANCELLED") {
            return error(label('AccountingErrorUiLabels', 'AccountingFinAccountStatusNotValidError'))
        }
    }
    Map partyInvoices = [:]
    parameters.invoiceIds.each {invoiceId ->
        GenericValue invoice = from("Invoice").where("invoiceId", invoiceId).queryOne()
        UtilMisc.addToListInMap(invoice, partyInvoices, invoice.partyIdFrom)
    }
    List paymentIds = []
    partyInvoices.each { partyId, invoice ->
        if (parameters.checkStartNumber) {
            parameters.checkStartNumber = parameters.checkStartNumber + 1
        }
        result = run service: 'createPaymentAndApplicationForParty', with: [*                  : parameters,
                                                                            paymentMethodTypeId: paymentMethod.paymentMethodTypeId,
                                                                            finAccountId       : paymentMethod.finAccountId,
                                                                            partyId            : partyId,
                                                                            invoices           : invoice]
        paymentIds << result.paymentId
    }
    if (paymentIds) {
        result = run service: 'createPaymentGroupAndMember', with: [paymentIds        : paymentIds,
                                                                    paymentGroupTypeId: 'CHECK_RUN',
                                                                    paymentGroupName  : "Payment group for Check Run(InvoiceIds-${parameters.invoiceIds})"]
        paymentGroupId = result.paymentGroupId
    }
    if (!result.paymentGroupId) {
        return error(label("AccountingUiLabels", "AccountingNoInvoicesReadyOrOutstandingAmountZero"))
    }
    return result
}

def createPaymentFromOrder() {
    GenericValue orderHeader = from("OrderHeader").where(parameters).queryOne()
    if (orderHeader) {
        if ("PURCHASE_ORDER" == orderHeader.orderTypeId) {
            String purchaseAutoCreate = UtilProperties.getPropertyValue('accounting', 'accounting.payment.purchaseorder.autocreate', 'Y')
            if (purchaseAutoCreate != "Y") {
                return error('payment not created from approved order because config (accounting.payment.salesorder.autocreate) is not set to Y (accounting.properties)')
            }
        } else if ("SALES_ORDER" == orderHeader.orderTypeId) {
            String salesAutoCreate = UtilProperties.getPropertyValue('accounting', 'accounting.payment.salesorder.autocreate', 'Y')
            if (salesAutoCreate != "Y") {
                return error('payment not created from approved order because config (accounting.payment.salesorder.autocreate) is not set to Y (accounting.properties)')
            }
        }

        /* check if orderPaymentPreference with payment already exist, if yes do not re-create */
        if (from("OrderPaymentPrefAndPayment")
                .where([EntityCondition.makeCondition("orderId", orderHeader.orderId),
                        EntityCondition.makeCondition("statusId", EntityOperator.NOT_EQUAL, "PAYMENT_CANCELLED")])
                .queryCount() > 1) {
            return error("Payment not created for order ${orderHeader.orderId}, at least a single payment already exists")
        }

        GenericValue orderRoleTo = from("OrderRole")
                .where(orderId: orderHeader.orderId,
                        roleTypeId: "BILL_FROM_VENDOR")
                .queryFirst()
        GenericValue orderRoleFrom = from("OrderRole")
                .where(orderId: orderHeader.orderId,
                        roleTypeId: "BILL_TO_CUSTOMER")
                .queryFirst()

        GenericValue agreement
        String organizationPartyId
        if ("PURCHASE_ORDER" == orderHeader.orderTypeId) {
            agreement = from("Agreement")
                    .where(partyIdFrom: orderRoleFrom.partyId,
                            partyIdTo: orderRoleTo.partyId,
                            agreementTypeId: "PURCHASE_AGREEMENT")
                    .filterByDate()
                    .queryFirst()
            parameters.paymentTypeId = "VENDOR_PAYMENT"
            organizationPartyId = orderRoleFrom.partyId
        } else {
            agreement = from("Agreement")
                    .where(partyIdFrom: orderRoleFrom.partyId,
                            partyIdTo: orderRoleTo.partyId,
                            agreementTypeId: "SALES_AGREEMENT")
                    .filterByDate()
                    .queryFirst()
            parameters.paymentTypeId = "CUSTOMER_PAYMENT"
            organizationPartyId = orderRoleTo.partyId
        }

        if (agreement) {
            GenericValue orderTerm = from("OrderTerm")
                    .where(orderId: orderHeader.orderId,
                            termTypeId: "FIN_PAYMENT_TERM")
                    .queryFirst()
            if (orderTerm && orderTerm.termDays) {
                parameters.effectiveDate = UtilDateTime.addDaysToTimestamp(UtilDateTime.nowTimestamp(), orderTerm.termDays)
            }
        }
        parameters.effectiveDate = parameters.effectiveDate ?: UtilDateTime.nowTimestamp()

        /* check currency and when required use invoice currency rate or convert when invoice not available */

        Map result = run service: 'getPartyAccountingPreferences', with: [organizationPartyId: organizationPartyId]
        GenericValue partyAcctgPreference = result.partyAccountingPreference
        if (partyAcctgPreference.baseCurrencyUomId &&
                orderHeader.currencyUom == partyAcctgPreference.baseCurrencyUomId) {
            parameters.currencyUomId = orderHeader.currencyUom
            parameters.amount = orderHeader.grandTotal

            /* get conversion rate from related invoice when exists */
            Map convertUomInMap = [originalValue: orderHeader.grandTotal,
                                   uomId        : orderHeader.currencyUom,
                                   uomIdTo      : partyAcctgPreference.baseCurrencyUomId]
            List<GenericValue> invoices = from("OrderItemBillingAndInvoiceAndItem")
                    .where(orderId: orderHeader.orderId)
                    .queryList()
            if (invoices) {
                GenericValue invoice = from("Invoice").where("invoiceId", invoices[0].invoiceId).queryOne()
                convertUomInMap.asOfDate = invoice.invoiceDate
            }
            logInfo("convertUomInMap = " + convertUomInMap)

            result = run service: 'convertUom', with: convertUomInMap
            parameters.amount = result.convertedValue

            parameters.actualCurrencyAmount = orderHeader.grandTotal
            parameters.actualCurrencyUomId = orderHeader.currencyUom
            parameters.currencyUomId = partyAcctgPreference.baseCurrencyUomId
        } else {
            parameters.currencyUomId = orderHeader.currencyUom
            parameters.amount = orderHeader.grandTotal
        }

        parameters.partyIdFrom = orderRoleFrom.partyId
        parameters.partyIdTo = orderRoleTo.partyId
        parameters.paymentMethodTypeId = "COMPANY_ACCOUNT"
        parameters.statusId = "PMNT_NOT_PAID"

        result = run service: 'createPayment', with: parameters
        parameters.paymentId = result.paymentId

        parameters.orderId = orderHeader.orderId
        parameters.maxAmount = orderHeader.grandTotal

        result = run service: 'createOrderPaymentPreference', with: parameters
        parameters.paymentPreferenceId = result.orderPaymentPreferenceId

        result = run service: 'updatePayment', with: parameters
        result.paymentId = parameters.paymentId
        logInfo('payment ' + parameters.paymentId + ' with the not-paid status automatically created from order: ' + parameters.orderId + ' (can be disabled in accounting.properties)')

        return result
    }
}

def createPaymentApplication() {
    // Create a Payment Application
    if (!parameters.invoiceId && !parameters.billingAccountId && !parameters.taxAuthGeoId && !parameters.toPaymentId) {
        return error(label("AccountingUiLabels", "AccountingPaymentApplicationParameterMissing"))
    }
    GenericValue paymentAppl = makeValue("PaymentApplication", parameters)

    GenericValue payment = from("Payment").where("paymentId", parameters.paymentId).queryOne()
    if (!payment) {
        return error(label("AccountingUiLabels", "AccountingPaymentApplicationParameterMissing"))
    }

    BigDecimal notAppliedPayment = PaymentWorker.getPaymentNotApplied(payment)

    if (parameters.invoiceId) {
        // get the invoice and do some further validation against it
        GenericValue invoice = from("Invoice").where("invoiceId", parameters.invoiceId).queryOne()
        // check the currencies if they are compatible
        if (invoice.currencyUomId != payment.currencyUomId && invoice.currencyUomId != payment.actualCurrencyUomId) {
            return error(label("AccountingUiLabels", "AccountingCurrenciesOfInvoiceAndPaymentNotCompatible"))
        }
        if (invoice.currencyUomId != payment.currencyUomId && invoice.currencyUomId == payment.actualCurrencyUomId) {
            // if required get the payment amount in foreign currency (local we already have)
            notAppliedPayment = PaymentWorker.getPaymentNotApplied(payment, true)
        }
        // get the amount that has not been applied yet for the invoice (outstanding amount)
        BigDecimal notAppliedInvoice = InvoiceWorker.getInvoiceNotApplied(invoice)
        paymentAppl.amountApplied = notAppliedInvoice <= notAppliedPayment
                ? notAppliedInvoice : notAppliedPayment

        if (invoice.billingAccountId) {
            paymentAppl.billingAccountId = invoice.billingAccountId
        }
    }

    if (parameters.toPaymentId) {
        // get the to payment and check the parent types are compatible
        GenericValue toPayment = from("Payment").where("paymentId", parameters.toPaymentId).queryOne()
        //  when amount not provided use the the lowest value available
        if (!parameters.amountApplied) {
            notAppliedPayment = PaymentWorker.getPaymentNotApplied(payment)
            BigDecimal notAppliedToPayment = PaymentWorker.getPaymentNotApplied(toPayment)
            paymentAppl.amountApplied = notAppliedPayment < notAppliedToPayment
                    ? notAppliedPayment : notAppliedToPayment
        }
    }

    if (!paymentAppl.amountApplied &&
            (parameters.billingAccountId || parameters.taxAuthGeoId)) {
        paymentAppl.amountApplied = notAppliedPayment
    }
    paymentAppl.paymentApplicationId = delegator.getNextSeqId("PaymentApplication")
    paymentAppl.create()

    return success([amountApplied       : paymentAppl.amountApplied,
                    paymentApplicationId: paymentAppl.paymentApplicationId,
                    paymentTypeId       : payment.paymentTypeId])
}