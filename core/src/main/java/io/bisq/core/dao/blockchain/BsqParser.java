/*
 * This file is part of bisq.
 *
 * bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package io.bisq.core.dao.blockchain;

import com.google.common.annotations.VisibleForTesting;
import io.bisq.common.app.DevEnv;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.concurrent.Immutable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkArgument;

// We are in threaded context. Don't mix up with UserThread.
@Slf4j
@Immutable
public class BsqParser {
    // todo just temp
    // Map<Integer, String> recursionMap = new HashMap<>();

    private final BsqBlockchainService bsqBlockchainService;

    public BsqParser(BsqBlockchainService bsqBlockchainService) {
        this.bsqBlockchainService = bsqBlockchainService;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Parsing
    ///////////////////////////////////////////////////////////////////////////////////////////

    @VisibleForTesting
    void parseBlocks(int startBlockHeight,
                     int chainHeadHeight,
                     int genesisBlockHeight,
                     String genesisTxId,
                     TxOutputMap txOutputMap,
                     Consumer<TxOutputMap> snapShotHandler) throws BsqBlockchainException {
        try {
            log.info("chainHeadHeight=" + chainHeadHeight);
            long startTotalTs = System.currentTimeMillis();
            for (int height = startBlockHeight; height <= chainHeadHeight; height++) {
                long startBlockTs = System.currentTimeMillis();
                com.neemre.btcdcli4j.core.domain.Block btcdBlock = bsqBlockchainService.requestBlock(height);
                log.debug("Current block height=" + height);

                // 1 block has about 3 MB, but we keep it only in memory as long as needed
                final BsqBlock bsqBlock = new BsqBlock(btcdBlock.getTx(), btcdBlock.getHeight());

                parseBlock(bsqBlock,
                        genesisBlockHeight,
                        genesisTxId,
                        txOutputMap);

                txOutputMap.setBlockHeight(height);

                if (BsqBlockchainManager.triggersSnapshot(height)) {
                    // We clone the map to isolate thread context. TxOutputMap is used in UserThread.
                    final TxOutputMap clonedSnapShotMap = TxOutputMap.getClonedMapUpToHeight(txOutputMap,
                            BsqBlockchainManager.getSnapshotHeight(height));
                    snapShotHandler.accept(clonedSnapShotMap);
                }
                
              /*  StringBuilder sb = new StringBuilder("recursionMap:\n");
                List<String> list = new ArrayList<>();
                //recursionMap.entrySet().stream().forEach(e -> sb.append(e.getKey()).append(": ").append(e.getValue()).append("\n"));
                recursionMap.entrySet().stream().forEach(e -> list.add("\nBlock height / Tx graph depth / Nr. of Txs: " + e.getKey()
                        + " / " + e.getValue()));
                Collections.sort(list);
                list.stream().forEach(e -> sb.append(e).append("\n"));
                log.warn(list.toString().replace(",", "").replace("[", "").replace("]", ""));*/

               /* log.info("Parsing for block {} took {} ms. Total: {} ms for {} blocks",
                        height,
                        (System.currentTimeMillis() - startBlockTs),
                        (System.currentTimeMillis() - startTotalTs),
                        (height - startBlockHeight + 1));
                Profiler.printSystemLoad(log);*/
            }
            log.info("Parsing for blocks {} to {} took {} ms",
                    startBlockHeight,
                    chainHeadHeight,
                    System.currentTimeMillis() - startTotalTs);
        } catch (Throwable t) {
            log.error(t.toString());
            t.printStackTrace();
            throw new BsqBlockchainException(t);
        }
    }

    void parseBlock(BsqBlock block,
                    int genesisBlockHeight,
                    String genesisTxId,
                    TxOutputMap txOutputMap)
            throws BsqBlockchainException {
        int blockHeight = block.getHeight();
        log.debug("Parse block at height={} ", blockHeight);
        // We add all transactions to the block
        List<String> txIds = block.getTxIds();
        Tx genesisTx = null;
        for (String txId : txIds) {
            final Tx tx = bsqBlockchainService.requestTransaction(txId, blockHeight);
            block.addTx(tx);
            if (txId.equals(genesisTxId))
                genesisTx = tx;
        }

        if (genesisTx != null) {
            checkArgument(blockHeight == genesisBlockHeight,
                    "If we have a matching genesis tx the block height must match as well");
            parseGenesisTx(genesisTx, txOutputMap);
        }
        //txSize = block.getTxList().size();

        // Worst case is that all txs in a block are depending on another, so only one get resolved at each iteration.
        // Min tx size is 189 bytes (normally about 240 bytes), 1 MB can contain max. about 5300 txs (usually 2000).
        // Realistically we don't expect more then a few recursive calls.
        // There are some blocks with testing such dependency chains like block 130768 where at each iteration only 
        // one get resolved.
        // Lately there is a patter with 24 iterations observed 
        parseTransactions(block.getTxList(), txOutputMap, blockHeight, 0, 5300);
    }

    @VisibleForTesting
    void parseGenesisTx(Tx tx,
                        TxOutputMap txOutputMap) {
        // Genesis tx uses all outputs as BSQ outputs
        tx.getOutputs().stream().forEach(txOutput -> {
            txOutput.setVerified(true);
            txOutput.setBsqCoinBase(true);
            txOutputMap.put(txOutput);
        });
    }

    // Recursive method
    // Performance-wise the recursion does not hurt (e.g. 5-20 ms). 
    // The RPC requestTransaction is the slow call.  
    void parseTransactions(List<Tx> transactions,
                           TxOutputMap txOutputMap,
                           int blockHeight,
                           int recursionCounter,
                           int maxRecursions) {
        //recursionMap.put(blockHeight, recursionCounter + " / " + txSize);

        // The set of txIds of txs which are used for inputs of another tx in same block
        Set<String> intraBlockSpendingTxIdSet = getIntraBlockSpendingTxIdSet(transactions);

        List<Tx> txsWithoutInputsFromSameBlock = new ArrayList<>();
        List<Tx> txsWithInputsFromSameBlock = new ArrayList<>();

        // First we find the txs which have no intra-block inputs
        outerLoop:
        for (Tx tx : transactions) {
            for (TxInput input : tx.getInputs()) {
                if (intraBlockSpendingTxIdSet.contains(input.getSpendingTxId())) {
                    // We have an input from one of the intra-block-transactions, so we cannot process that tx now.
                    // We add the tx for later parsing to the txsWithInputsFromSameBlock and move to the next tx.
                    txsWithInputsFromSameBlock.add(tx);
                    continue outerLoop;
                }
            }
            // If we have not found any tx input pointing to anther tx in the same block we add it to our
            // txsWithoutInputsFromSameBlock.
            txsWithoutInputsFromSameBlock.add(tx);
        }
        checkArgument(txsWithInputsFromSameBlock.size() + txsWithoutInputsFromSameBlock.size() == transactions.size(),
                "txsWithInputsFromSameBlock.size + txsWithoutInputsFromSameBlock.size != transactions.size");

        // Usual values is up to 25
        // There are some blocks where it seems devs have tested graphs of many depending txs, but even 
        // those dont exceed 200 recursions and are mostly old blocks from 2012 when fees have been low ;-).
        // TODO check strategy btc core uses (sorting the dependency graph would be an optimisation)
        // Seems btc core delivers tx list sorted by dependency graph. -> TODO verify and test
        if (recursionCounter > 100) {
            log.warn("Unusual high recursive calls at resolveConnectedTxs. recursionCounter=" + recursionCounter);
            log.warn("blockHeight=" + blockHeight);
            log.warn("txsWithoutInputsFromSameBlock.size " + txsWithoutInputsFromSameBlock.size());
            log.warn("txsWithInputsFromSameBlock.size " + txsWithInputsFromSameBlock.size());
            //  log.warn("txsWithInputsFromSameBlock " + txsWithInputsFromSameBlock.stream().map(e->e.getId()).collect(Collectors.toList()));
        }

        // we check if we have any valid BSQ from that tx set
        if (!txsWithoutInputsFromSameBlock.isEmpty()) {
            for (Tx tx : txsWithoutInputsFromSameBlock) {
                parseTx(tx, blockHeight, txOutputMap);
            }
            log.debug("Parsing of all txsWithoutInputsFromSameBlock is done.");
        }

        // we check if we have any valid BSQ utxo from that tx set
        // We might have InputsFromSameBlock which are BTC only but not BSQ, so we cannot 
        // optimize here and need to iterate further.
        if (!txsWithInputsFromSameBlock.isEmpty()) {
            if (recursionCounter < maxRecursions) {
                parseTransactions(txsWithInputsFromSameBlock, txOutputMap, blockHeight,
                        ++recursionCounter, maxRecursions);
            } else {
                final String msg = "We exceeded our max. recursions for resolveConnectedTxs.\n" +
                        "txsWithInputsFromSameBlock=" + txsWithInputsFromSameBlock.toString() + "\n" +
                        "txsWithoutInputsFromSameBlock=" + txsWithoutInputsFromSameBlock;
                log.warn(msg);
                if (DevEnv.DEV_MODE)
                    throw new RuntimeException(msg);
            }
        } else {
            log.debug("We have no more txsWithInputsFromSameBlock.");
        }
    }

    private void parseTx(Tx tx,
                         int blockHeight,
                         TxOutputMap txOutputMap) {
        List<TxOutput> outputs = tx.getOutputs();
        long availableValue = 0;
        final String txId = tx.getId();
        for (int inputIndex = 0; inputIndex < tx.getInputs().size(); inputIndex++) {
            TxInput input = tx.getInputs().get(inputIndex);
            final TxOutput txOutputFromSpendingTx = txOutputMap.get(input.getSpendingTxId(), input.getSpendingTxOutputIndex());
            if (txOutputFromSpendingTx != null &&
                    txOutputFromSpendingTx.isVerified() &&
                    txOutputFromSpendingTx.isUnSpend()) {
                txOutputFromSpendingTx.setSpendInfo(new SpendInfo(blockHeight, txId, inputIndex));
                availableValue = availableValue + txOutputFromSpendingTx.getValue();
            }
        }

        // If we have an input spending tokens we iterate the outputs
        if (availableValue > 0) {
            // We use order of output index. An output is a BSQ utxo as long there is enough input value
            for (TxOutput txOutput : outputs) {
                final long txOutputValue = txOutput.getValue();
                if (availableValue >= txOutputValue) {
                    // We are spending available tokens
                    txOutput.setVerified(true);
                    txOutputMap.put(txOutput);
                    availableValue -= txOutputValue;
                    if (availableValue == 0) {
                        log.debug("We don't have anymore BSQ to spend");
                        break;
                    }
                } else {
                    break;
                }
            }
        }

        if (availableValue > 0) {
            log.debug("BSQ have been left which was not spent. Burned BSQ amount={}, tx={}",
                    availableValue,
                    tx.toString());
            final long finalAvailableValue = availableValue;
            tx.getOutputs().stream().forEach(e -> e.setBurnedFee(finalAvailableValue));
        }
    }

    private Set<String> getIntraBlockSpendingTxIdSet(List<Tx> transactions) {
        Set<String> txIdSet = transactions.stream().map(Tx::getId).collect(Collectors.toSet());
        Set<String> intraBlockSpendingTxIdSet = new HashSet<>();
        transactions.stream()
                .forEach(tx -> tx.getInputs().stream()
                        .filter(input -> txIdSet.contains(input.getSpendingTxId()))
                        .forEach(input -> intraBlockSpendingTxIdSet.add(input.getSpendingTxId())));
        return intraBlockSpendingTxIdSet;
    }
}