/*
 * Copyright (c) 2017 Sergey Chernov, iCodici S.n.C, All Rights Reserved  
 *  
 * Written by Sergey Chernov <real.sergeych@gmail.com>, August 2017.
 *
 */

package com.icodici.universa.contract;

import com.icodici.crypto.EncryptionError;
import com.icodici.crypto.PrivateKey;
import com.icodici.crypto.PublicKey;
import com.icodici.universa.*;
import com.icodici.universa.contract.permissions.*;
import com.icodici.universa.contract.roles.ListRole;
import com.icodici.universa.contract.roles.Role;
import com.icodici.universa.contract.roles.RoleLink;
import com.icodici.universa.contract.roles.SimpleRole;
import com.icodici.universa.node.ItemResult;
import com.icodici.universa.node2.Config;
import com.icodici.universa.node2.Quantiser;
import net.sergeych.biserializer.*;
import net.sergeych.boss.Boss;
import net.sergeych.collections.Multimap;
import net.sergeych.tools.Binder;
import net.sergeych.tools.Do;
import net.sergeych.utils.Bytes;
import net.sergeych.utils.Ut;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.yaml.snakeyaml.Yaml;

import java.io.FileReader;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.chrono.ChronoZonedDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.icodici.crypto.PublicKey.PUBLIC_KEY_BI_ADAPTER;
import static com.icodici.universa.Errors.*;
import static java.util.Arrays.asList;

@BiType(name = "UniversaContract")
public class Contract implements Approvable, BiSerializable, Cloneable {

    private static final int MAX_API_LEVEL = 3;
    private final Set<Contract> revokingItems = new HashSet<>();
    private final Set<Contract> newItems = new HashSet<>();
    private final Map<String, Role> roles = new HashMap<>();
    private Definition definition;
    private State state;
    private Transactional transactional;
    private byte[] sealedBinary;
    private int apiLevel = MAX_API_LEVEL;
    private Context context = null;

    /**
     * true if the contract was imported from sealed capsule
     */
    private boolean isSealed = false;
    private final Map<PublicKey, ExtendedSignature> sealedByKeys = new HashMap<>();
    private Set<PrivateKey> keysToSignWith = new HashSet<>();
    private HashId id;
    private TransactionPack transactionPack;

    public Quantiser getQuantiser() {
        return quantiser;
    }

    /**
     * Instance that keep cost of processing contract
     */
    private Quantiser quantiser = new Quantiser();

    public static int getTestQuantaLimit() {
        return testQuantaLimit;
    }

    public static void setTestQuantaLimit(int testQuantaLimit) {
        Contract.testQuantaLimit = testQuantaLimit;
    }

    private static int testQuantaLimit = -1;

    /**
     * Extract contract from v2 or v3 sealed form, getting revokein and new items from the transaction pack supplied. If
     * the transaction pack fails to resove a link, no error will be reported - not sure it's a good idea. If need, the
     * exception could be generated with the transaction pack.
     * <p>
     * It is recommended to call {@link #check()} after construction to see the errors.
     *
     * @param sealed binary sealed contract.
     * @param pack   the transaction pack to resolve dependeincise agains.
     *
     * @throws IllegalArgumentException on the various format errors
     */
    public Contract(byte[] sealed, @NonNull TransactionPack pack) throws IOException {
        this.quantiser.reset(testQuantaLimit); // debug const. need to get quantaLimit from TransactionPack here

        this.sealedBinary = sealed;
        this.transactionPack = pack;
        Binder data = Boss.unpack(sealed);
        if (!data.getStringOrThrow("type").equals("unicapsule"))
            throw new IllegalArgumentException("wrong object type, unicapsule required");

        apiLevel = data.getIntOrThrow("version");

        byte[] contractBytes = data.getBinaryOrThrow("data");

        // This must be explained. By default, Boss.load will apply contract transformation in place
        // as it is registered BiSerializable type, and we want to avoid it. Therefore, we decode boss
        // data without BiSerializer and then do it by hand calling deserialize:
        Binder payload = Boss.load(contractBytes, null);
        BiDeserializer bm = BossBiMapper.newDeserializer();
        deserialize(payload.getBinderOrThrow("contract"), bm);

        if (apiLevel < 3) {
            // Legacy format: revoking and new items are included (so the contract pack grows)
            for (Object packed : payload.getList("revoking", Collections.EMPTY_LIST)) {
                Contract c = new Contract(((Bytes) packed).toArray(), pack);
                revokingItems.add(c);
                pack.addReference(c);
            }

            for (Object packed : payload.getList("new", Collections.EMPTY_LIST)) {
                Contract c = new Contract(((Bytes) packed).toArray(), pack);
                newItems.add(c);
                pack.addReference(c);
            }
        } else {
            // new format: only references are included
            for (Binder b : (List<Binder>) payload.getList("revoking", Collections.EMPTY_LIST)) {
                HashId hid = HashId.withDigest(b.getBinaryOrThrow("sha512"));
                Contract r = pack.getReference(hid);
                if (r != null) {
                    revokingItems.add(r);
                }
            }
            for (Binder b : (List<Binder>) payload.getList("new", Collections.EMPTY_LIST)) {
                HashId hid = HashId.withDigest(b.getBinaryOrThrow("sha512"));
                Contract n = pack.getReference(hid);
                if (n != null) {
                    newItems.add(n);
                }
            }
        }

        // if exist siblings for contract (more then itself)
        getContext();
        if(getSiblings().size() > 1) {
            newItems.forEach(i -> i.context = context);
        }

        HashMap<Bytes, PublicKey> keys = new HashMap<Bytes, PublicKey>();

        roles.values().forEach(role -> {
            role.getKeys().forEach(key -> keys.put(ExtendedSignature.keyId(key), key));
        });

        for (Object signature : (List) data.getOrThrow("signatures")) {
            byte[] s = ((Bytes) signature).toArray();
            Bytes keyId = ExtendedSignature.extractKeyId(s);
            PublicKey key = keys.get(keyId);
            if (key != null) {
                verifySignatureQuantized(key);
                ExtendedSignature es = ExtendedSignature.verify(key, s, contractBytes);
                if (es != null) {
                    sealedByKeys.put(key, es);
                } else
                    addError(Errors.BAD_SIGNATURE, "keytag:" + key.info().getBase64Tag(), "the signature is broken");
            }
        }
    }

    public Contract(byte[] data) throws IOException {
        this(data, new TransactionPack());
    }


    /**
     * Extract old, deprecated v2 self-contained binary partially unpacked by the {@link TransactionPack}, and fill the
     * transaction pack with its contents. This contsructor also fills transaction pack instance with the new and
     * revoking items included in its body in v2.
     *
     * @param sealed binary sealed contract
     * @param data   unpacked sealed data (it is ready by the time of calling it)
     *
     * @throws IllegalArgumentException on the various format errors
     */
    public Contract(byte[] sealed, Binder data, TransactionPack pack) throws IOException {
        this.quantiser.reset(testQuantaLimit); // debug const. need to get quantaLimit from TransactionPack here

        this.sealedBinary = sealed;
        if (!data.getStringOrThrow("type").equals("unicapsule"))
            throw new IllegalArgumentException("wrong object type, unicapsule required");
        int v = data.getIntOrThrow("version");
        if (v > 2)
            throw new IllegalArgumentException("This constructor requires version 2, got version " + v);
        byte[] contractBytes = data.getBinaryOrThrow("data");

        // This must be explained. By default, Boss.load will apply contract transformation in place
        // as it is registered BiSerializable type, and we want to avoid it. Therefore, we decode boss
        // data without BiSerializer and then do it by hand calling deserialize:
        Binder payload = Boss.load(contractBytes, null);
        BiDeserializer bm = BossBiMapper.newDeserializer();
        deserialize(payload.getBinderOrThrow("contract"), bm);

        for (Object r : payload.getList("revoking", Collections.EMPTY_LIST)) {
            Contract c = new Contract(((Bytes) r).toArray(), pack);
            revokingItems.add(c);
            pack.addReference(c);
        }

        for (Object r : payload.getList("new", Collections.EMPTY_LIST)) {
            Contract c = new Contract(((Bytes) r).toArray(), pack);
            newItems.add(c);
            pack.addReference(c);
        }

        // if exist siblings for contract (more then itself)
        getContext();
        if(getSiblings().size() > 1) {
            newItems.forEach(i -> i.context = context);
        }

        HashMap<Bytes, PublicKey> keys = new HashMap<Bytes, PublicKey>();

        roles.values().forEach(role -> {
            role.getKeys().forEach(key -> keys.put(ExtendedSignature.keyId(key), key));
        });

        for (Object signature : (List) data.getOrThrow("signatures")) {
            byte[] s = ((Bytes) signature).toArray();
            Bytes keyId = ExtendedSignature.extractKeyId(s);
            PublicKey key = keys.get(keyId);
            if (key != null) {
                verifySignatureQuantized(key);
                ExtendedSignature es = ExtendedSignature.verify(key, s, contractBytes);
                if (es != null) {
                    sealedByKeys.put(key, es);
                } else
                    addError(Errors.BAD_SIGNATURE, "keytag:" + key.info().getBase64Tag(), "the signature is broken");
            }
        }
    }


    public Contract() {
        this.quantiser.reset(testQuantaLimit); // debug const. need to get quantaLimit from TransactionPack here

        definition = new Definition();
        state = new State();
    }

    /**
     * Create a default empty new contract using a provided key as issuer and owner and sealer. Default expiration is
     * set to 5 years.
     * <p>
     * This constructor adds key as sealing signature so it is ready to {@link #seal()} just after construction, thought
     * it is necessary to put real data to it first. It is allowed to change owner, expiration and data fields after
     * creation (but before sealing).
     *
     * @param key
     */
    public Contract(PrivateKey key) {
        this();
        // default expiration date
        setExpiresAt(ZonedDateTime.now().plusDays(90));
        // issuer role is a key for a new contract
        Role r = setIssuerKeys(key.getPublicKey());
        // issuer is owner, link roles
        registerRole(new RoleLink("owner", "issuer"));
        registerRole(new RoleLink("creator", "issuer"));
        // owner can change permission
        addPermission(new ChangeOwnerPermission(r));
        // issuer should sign
        addSignerKey(key);
    }

    public List<ErrorRecord> getErrors() {
        return errors;
    }

    private final List<ErrorRecord> errors = new ArrayList<>();

    private Contract initializeWithDsl(Binder root) throws EncryptionError {
        apiLevel = root.getIntOrThrow("api_level");
        definition = new Definition().initializeWithDsl(root.getBinder("definition"));
        state = new State().initializeWithDsl(root.getBinder("state"));
        // now we have all roles, we can build permissions:
        definition.scanDslPermissions();
        return this;
    }

    public static Contract fromDslFile(String fileName) throws IOException {
        Yaml yaml = new Yaml();
        try (FileReader r = new FileReader(fileName)) {
            Binder binder = Binder.from(DefaultBiMapper.deserialize((Map) yaml.load(r)));
            return new Contract().initializeWithDsl(binder);
        }
    }

    public State getState() {
        return state;
    }

    public Transactional getTransactional() {
        return transactional;
    }

    public int getApiLevel() {
        return apiLevel;
    }

    public void setApiLevel(int apiLevel) {
        this.apiLevel = apiLevel;
    }

    @Override
    public Set<Reference> getReferencedItems() {
        Set<Reference> referencedItems = new HashSet<>();
        if (transactional != null && transactional.references != null)
            referencedItems.addAll(transactional.references);
        if (definition != null && definition.getReferences() != null)
            referencedItems.addAll(definition.getReferences());
        return referencedItems;
    }

    @Override
    public Set<Approvable> getRevokingItems() {
        return (Set) revokingItems;
    }

    @Override
    public Set<Approvable> getNewItems() {
        return (Set) newItems;
    }

    public List<Contract> getAllContractInTree() {

        List<Contract> contracts = new ArrayList<>();
        contracts.add(this);

        for (Contract c : getNew()) {
            contracts.addAll(c.getAllContractInTree());
        }

        for (Contract c : getRevoking()) {
            contracts.addAll(c.getAllContractInTree());
        }

        return contracts;
    }

    @Override
    public boolean check(String prefix) throws Quantiser.QuantiserException {
        return check(prefix, null);
    }

    private boolean check(String prefix, List<Contract> contractsTree) throws Quantiser.QuantiserException {

        // now we looking for references only in one level of tree - among neighbours
        // but for main contract (not from new items) we looking for
        // references among new items
        if (contractsTree == null)
            contractsTree = getAllContractInTree();

        quantiser.reset(quantiser.getQuantaLimit());
        // Add key verify quanta again (we just reset quantiser)
        for (PublicKey key : sealedByKeys.keySet()) {
            if (key != null) {
                verifySignatureQuantized(key);
            }
        }

        // Add register a version quanta (for self)
        quantiser.addWorkCost(Quantiser.QuantiserProcesses.PRICE_REGISTER_VERSION);

        // quantize revokingItems and referencedItems
        for (Contract r : revokingItems) {
            // Add key verify quanta for each revoking
            for (PublicKey key : r.sealedByKeys.keySet()) {
                if (key != null) {
                    verifySignatureQuantized(key);
                }
            }
            quantiser.addWorkCost(Quantiser.QuantiserProcesses.PRICE_REVOKE_VERSION);
        }
        for (int i = 0; i < getReferencedItems().size(); i++) {
            quantiser.addWorkCost(Quantiser.QuantiserProcesses.PRICE_CHECK_REFERENCED_VERSION);
        }

        try {
            // common check for all cases
            errors.clear();
            basicCheck();
            if (state.origin == null)
                checkRootContract();
            else
                checkChangedContract();
        } catch (Quantiser.QuantiserException e) {
            throw e;
        } catch (Exception e) {
            e.printStackTrace();
            addError(FAILED_CHECK, prefix, e.toString());
        }
        int index = 0;
        for (Contract c : newItems) {
            String p = prefix + "new[" + index + "].";
            checkSubItemQuantized(c, p, contractsTree);
            if (!c.isOk()) {
                c.errors.forEach(e -> {
                    String name = e.getObjectName();
                    name = name == null ? p : p + name;
                    addError(e.getError(), name, e.getMessage());
                });
            }
            index++;
        }
        checkDupesCreation();

        checkReferencedItems(contractsTree);

        return errors.size() == 0;
    }

    private boolean checkReferencedItems(List<Contract> neighbourContracts) throws Quantiser.QuantiserException {

        if (getReferencedItems().size() == 0) {
            // if contract has no references -> then it's checkReferencedItems check is ok
            return true;
        }

        // check each reference, all must be ok
        boolean allRefs_check = true;
        for (final Reference rm : getReferencedItems()) {
            // use all neighbourContracts to check reference. at least one must be ok
            boolean rm_check = false;
            for (int j = 0; j < neighbourContracts.size(); ++j) {
                Contract neighbour = neighbourContracts.get(j);
                if ((rm.transactional_id != null && neighbour.transactional != null && rm.transactional_id.equals(neighbour.transactional.id)) ||
                        (rm.contract_id != null && rm.contract_id.equals(neighbour.id)))
                    if (checkOneReference(rm, neighbour)) {
                        rm_check = true;
                    }
            }

            if (rm_check == false) {
                allRefs_check = false;
                addError(Errors.FAILED_CHECK, "checkReferencedItems for contract (hashId="+getId().toString()+"): false");
            }
        }

        return allRefs_check;
    }

    private boolean checkOneReference(final Reference rm, final Contract refContract) throws Quantiser.QuantiserException {
        boolean res = true;

        if (rm.type == Reference.TYPE_EXISTING) {
//            res = false;
//            addError(Errors.UNKNOWN_COMMAND, "Reference.TYPE_EXISTING not implemented");
        } else if (rm.type == Reference.TYPE_TRANSACTIONAL) {
            if ((rm.transactional_id == null) ||
                (refContract.transactional == null) ||
                (refContract.transactional.getId() == null) ||
                "".equals(rm.transactional_id) ||
                "".equals(refContract.transactional.id)) {
                res = false;
                addError(Errors.BAD_REF, "transactional is missing");
            } else {
                if (rm.transactional_id != null && refContract.transactional == null) {
                    res = false;
                    addError(Errors.BAD_REF, "transactional not found");
                } else if (!rm.transactional_id.equals(refContract.transactional.id)) {
                    res = false;
                    addError(Errors.BAD_REF, "transactional_id mismatch");
                }
            }
        }

        if (rm.contract_id != null) {
            if (!rm.contract_id.equals(refContract.id)) {
                res = false;
                addError(Errors.BAD_REF, "contract_id mismatch");
            }
        }

        if (rm.origin != null) {
            if (!rm.origin.equals(refContract.getOrigin())) {
                res = false;
                addError(Errors.BAD_REF, "origin mismatch");
            }
        }

        for (Role refRole : rm.signed_by) {
            if (!refContract.isSignedBy(refRole)) {
                res = false;
                addError(Errors.BAD_SIGNATURE, "fingerprint mismatch");
            }
        }

        return res;
    }

    public boolean paymentCheck(PublicKey issuerKey) throws Quantiser.QuantiserException {
        boolean res = true;
        // Checks that there is a payment contract and the payment should be >= 1
        int transaction_units = getStateData().getInt("transaction_units", 0);
        if (transaction_units <= 0) {
            res = false;
            addError(Errors.BAD_VALUE, "transaction_units <= 0");
        }

        // check valid name/type fields combination
        Object o = getStateData().get("transaction_units");
        if (o == null || o.getClass() != Integer.class) {
            res = false;
            addError(Errors.BAD_VALUE, "transaction_units name/type mismatch");
        }

        // check valid decrement_permission
        if (!isPermitted("decrement_permission", getOwner())) {
            res = false;
            addError(Errors.BAD_VALUE, "decrement_permission is missing");
        }

        // The TU contract is checked to have valid issuer key (one of preset URS keys)
        if (!getIssuer().getKeys().equals(new HashSet<>(Arrays.asList(issuerKey)))) {
            res = false;
            addError(Errors.BAD_VALUE, "issuerKeys is not valid");
        }

        // If the check is failed, checking process is aborting
        if (!res) {
            return res;
        }

        // check if payment contract not origin itself, means has revision more then 1
        // don't make this check for initial transaction_units' contract
        if ((getRevision() != 1) || (getParent()!=null)) {
            if (getOrigin().equals(getId())) {
                res = false;
                addError(Errors.BAD_VALUE, "can't origin itself");
            }
            if (getRevision() <= 1) {
                res = false;
                addError(Errors.BAD_VALUE, "revision must be greater than 1");
            }

            // The TU is checked for its parent validness, it should be in the revoking items
            if (revokingItems.size() != 1) {
                res = false;
                addError(Errors.BAD_REVOKE, "revokingItems.size != 1");
            } else {
                Contract revoking = revokingItems.iterator().next();
                if (!revoking.getOrigin().equals(getOrigin())) {
                    res = false;
                    addError(Errors.BAD_REVOKE, "origin mismatch");
                }
            }
        }

        if (!res)
            return res;
        else
            res = check("");

        return res;
    }

    public int getProcessedCost() {
        return quantiser.getQuantaSum();
    }

    public int getProcessedCostUTN() {
        return (int) Math.floor(quantiser.getQuantaSum() / Quantiser.quantaPerUTN) + 1;
    }

    /**
     * All new items and self must have uniqie identication for its level, e.g. origin + revision + branch should always
     * ve different.
     */
    private void checkDupesCreation() {
        if (newItems.isEmpty())
            return;
        Set<String> revisionIds = new HashSet<>();
        revisionIds.add(getRevisionId());
        int count = 0;
        for (Contract c : newItems) {
            String i = c.getRevisionId();
            if (revisionIds.contains(i)) {
                addError(Errors.BAD_VALUE, "new[" + count + "]", "duplicated revision id: " + i);
            } else
                revisionIds.add(i);
            count++;
        }
    }

    public String getRevisionId() {
        String parentId = getParent() == null ? "" : (getParent().toBase64String() + "/");
        StringBuilder sb = new StringBuilder(getOrigin().toBase64String() + "/" + parentId + state.revision);
        if (state.branchId != null)
            sb.append("/" + state.branchId.toString());
        return sb.toString();
    }

    /**
     * Create new root contract to be created. It may have parent, but does not have origin, as it is an origin itself.
     */
    private void checkRootContract() throws Quantiser.QuantiserException {
        // root contract must be issued ny the issuer
        Role issuer = getRole("issuer");
        if (issuer == null || !issuer.isValid()) {
            addError(BAD_VALUE, "definition.issuer", "missing issuer");
            return;
        }
        // the bad case - no issuer - should be processed normally without exceptions:
        Role createdBy = getRole("creator");
        if (createdBy == null || !createdBy.isValid()) {
            addError(BAD_VALUE, "state.created_by", "invalid creator");
            return;
        }
        if (issuer != null && !issuer.equalKeys(createdBy))
            addError(ISSUER_MUST_CREATE, "state.created_by");
        if (state.revision != 1)
            addError(BAD_VALUE, "state.revision", "must be 1 in a root contract");
        if (state.createdAt == null)
            state.createdAt = definition.createdAt;
        else if (!state.createdAt.equals(definition.createdAt))
            addError(BAD_VALUE, "state.created_at", "invalid");
        if (state.origin != null)
            addError(BAD_VALUE, "state.origin", "must be empty in a root contract");

        checkRootDependencies();
    }

    private void checkRootDependencies() throws Quantiser.QuantiserException {
        // Revoke dependencies: _issuer_ of the root contract must have right to revoke
        for (Approvable item : revokingItems) {
            if (!(item instanceof Contract))
                addError(BAD_REF, "revokingItem", "revoking item is not a Contract");
            Contract rc = (Contract) item;
            if (!rc.isPermitted("revoke", getIssuer()))
                addError(FORBIDDEN, "revokingItem", "revocation not permitted for item " + rc.getId());
        }
    }

    public void addError(Errors code, String field, String text) {
        Errors code1 = code;
        String field1 = field;
        String text1 = text;
        errors.add(new ErrorRecord(code1, field1, text1));
    }

    private void checkChangedContract() throws Quantiser.QuantiserException {
        // get context if not got yet
        getContext();
        Contract parent;
        // if exist siblings for contract (more then itself)
        if(getSiblings().size() > 1) {
            parent = getContext().base;
        } else {
            parent = getRevokingItem(getParent());
        }
        if (parent == null) {
            addError(BAD_REF, "parent", "parent contract must be included");
        } else {
            // checking parent:
            // proper origin
            HashId rootId = parent.getRootId();
            if (!rootId.equals(getRawOrigin())) {
                addError(BAD_VALUE, "state.origin", "wrong origin, should be root");
            }
            if (!getParent().equals(parent.getId()))
                addError(BAD_VALUE, "state.parent", "illegal parent references");

            ContractDelta delta = new ContractDelta(parent, this);
            delta.check();
        }
    }

    /**
     * Get the root id with the proper logic: for the root contract, it returns its {@link #getId()}, for the child
     * contracts - the origin id (state.origin field value). Note that all contract states in the chain share same
     * origin - it is an id of the root contract, whose id is always null.
     *
     * @return id of the root contract.
     */
    protected HashId getRootId() {
        HashId origin = getRawOrigin();
        return origin == null ? getId() : origin;
    }

    /**
     * Try to find revoking item with a given ID. If the matching item exists but is not a {@link Contract} instance, it
     * will not be found, null will be returned.
     *
     * @param id to find
     *
     * @return matching Contract instance or null if not found.
     */
    private Contract getRevokingItem(HashId id) {
        for (Approvable a : revokingItems) {
            if (a.getId().equals(id) && a instanceof Contract)
                return (Contract) a;
        }
        return null;
    }

    /**
     * Add one or more contracts to revoke. The contracts must be approved loaded from a binary. Do not call {@link
     * #seal()} on them as resealing discards network approval by changing the id!
     *
     * @param toRevoke
     */
    public void addRevokingItems(Contract... toRevoke) {
        for (Contract c : toRevoke) {
            revokingItems.add(c);
        }
    }

    private void basicCheck() throws Quantiser.QuantiserException {
        if (definition.createdAt == null ||
                definition.createdAt.isAfter(ZonedDateTime.now()) ||
                definition.createdAt.isBefore(getEarliestCreationTime())) {
            addError(BAD_VALUE, "definition.created_at", "invalid");
        }

        boolean stateExpiredAt = state.expiresAt == null || state.expiresAt.isBefore(ZonedDateTime.now());
        boolean definitionExpiredAt = definition.expiresAt == null || definition.expiresAt.isBefore(ZonedDateTime.now());

        if (stateExpiredAt) {
            if (definitionExpiredAt) {
                addError(EXPIRED, "state.expires_at");
            }
        }

        if (state.createdAt == null ||
                state.createdAt.isAfter(ZonedDateTime.now()) ||
                state.createdAt.isBefore(getEarliestCreationTime())) {
            addError(BAD_VALUE, "state.created_at");
        }
        if (apiLevel < 1)
            addError(BAD_VALUE, "api_level");
        Role owner = getRole("owner");
        if (owner == null || !owner.isValid())
            addError(MISSING_OWNER, "state.owner");
        Role issuer = getRole("issuer");
        if (issuer == null || !issuer.isValid())
            addError(MISSING_ISSUER, "state.issuer");
        if (state.revision < 1)
            addError(BAD_VALUE, "state.revision");
        Role createdBy = getRole("creator");
        if (createdBy == null || !createdBy.isValid())
            addError(BAD_VALUE, "state.created_by");
        if (!isSignedBy(createdBy))
            addError(NOT_SIGNED, "", "missing creator signature(s)");
    }

    private boolean isSignedBy(Role role) throws Quantiser.QuantiserException {
        if (role == null)
            return false;

        role = role.resolve();

        if (role == null)
            return false;

        if (!sealedByKeys.isEmpty())
            return role.isAllowedForKeys(getSealedByKeys());
        return role.isAllowedForKeys(
                getKeysToSignWith()
                        .stream()
                        .map(k -> k.getPublicKey())
                        .collect(Collectors.toSet())

        );
    }

    /**
     * Resolve object describing role and create either: - new role object - symlink to named role instance, ensure it
     * is register and return it, if it is a Map, tries to construct and register {@link Role} then return it.
     *
     * @param roleObject
     *
     * @return
     */
    @NonNull
    protected Role createRole(String roleName, Object roleObject) {
        if (roleObject instanceof CharSequence) {
            return registerRole(new RoleLink(roleName, roleObject.toString()));
        }
        if (roleObject instanceof Role)
            if(((Role)roleObject).getName() != null && ((Role)roleObject).getName().equals(roleName))
                return registerRole(((Role) roleObject));
            else
                return registerRole(((Role) roleObject).linkAs(roleName));
        if (roleObject instanceof Map) {
            Role r = Role.fromDslBinder(roleName, Binder.from(roleObject));
            return registerRole(r);
        }
        throw new IllegalArgumentException("cant make role from " + roleObject);
    }

    public Role getRole(String roleName) {
        return roles.get(roleName);
    }

    /**
     * Get the id sealing self if need
     *
     * @param sealAsNeed true to seal the contract if there is no {@link #getLastSealedBinary()}.
     *
     * @return contract id.
     */
    public HashId getId(boolean sealAsNeed) {
        if (id != null)
            return id;
        if (getLastSealedBinary() == null && sealAsNeed)
            seal();
        return getId();
    }

    @Override
    public HashId getId() {
        if (id == null) {
            if (sealedBinary != null)
                id = new HashId(sealedBinary);
            else
                throw new IllegalStateException("the contract has no binary attached, no Id could be calculated");
        }
        return id;
    }


    public Role getIssuer() {
        // maybe we should cache it
        return getRole("issuer");
    }

    @Override
    public ZonedDateTime getCreatedAt() {
        return definition.createdAt;
    }

    @Override
    public ZonedDateTime getExpiresAt() {
        return state.expiresAt != null ? state.expiresAt : definition.expiresAt;
    }

    public Map<String, Role> getRoles() {
        return roles;
    }

    public Definition getDefinition() {
        return definition;
    }

    public KeyRecord testGetOwner() {
        return getRole("owner").getKeyRecords().iterator().next();
    }

    public Role registerRole(Role role) {
        String name = role.getName();
        roles.put(name, role);
        role.setContract(this);
        return role;
    }

    public boolean isPermitted(String permissionName, KeyRecord keyRecord) throws Quantiser.QuantiserException {
        return isPermitted(permissionName, keyRecord.getPublicKey());
    }

    private Set<String> permissionIds;

    public void addPermission(Permission perm) {
        // We need to assign contract-unique id
        if (perm.getId() == null) {
            if (permissionIds == null) {
                permissionIds =
                        getPermissions().values().stream()
                                .map(x -> x.getId())
                                .collect(Collectors.toSet());
            }
            while (true) {
                String id = Ut.randomString(6);
                if (!permissionIds.contains(id)) {
                    permissionIds.add(id);
                    perm.setId(id);
                    break;
                }
            }
        }
        permissions.put(perm.getName(), perm);
    }

    public boolean isPermitted(String permissionName, PublicKey key) throws Quantiser.QuantiserException {
        Collection<Permission> cp = permissions.get(permissionName);
        if (cp != null) {
            for (Permission p : cp) {
                if (p.isAllowedForKeys(key)){
                    checkApplicablePermissionQuantized(p);
                    return true;
                }
            }
        }
        return false;
    }

    public boolean isPermitted(String permissionName, Collection<PublicKey> keys) throws Quantiser.QuantiserException {
        Collection<Permission> cp = permissions.get(permissionName);
        if (cp != null) {
            for (Permission p : cp) {
                if (p.isAllowedForKeys(keys)) {
                    checkApplicablePermissionQuantized(p);
                    return true;
                }
            }
        }
        return false;
    }

    public boolean isPermitted(String permissionName, Role role) throws Quantiser.QuantiserException {
        return isPermitted(permissionName, role.getKeys());
    }

    protected void addError(Errors code, String field) {
        Errors code1 = code;
        String field1 = field;
        errors.add(new ErrorRecord(code1, field1, ""));
    }

    public ChronoZonedDateTime<?> getEarliestCreationTime() {
        return ZonedDateTime.now().minusDays(10);
    }

    public Set<PublicKey> getSealedByKeys() {
        return sealedByKeys.keySet();
    }

    public Set<PrivateKey> getKeysToSignWith() {
        return keysToSignWith;
    }

    public void setKeysToSignWith(Set<PrivateKey> keysToSignWith) {
        this.keysToSignWith = keysToSignWith;
    }

    public void addSignerKeyFromFile(String fileName) throws IOException {
        addSignerKey(new PrivateKey(Do.read(fileName)));
    }

    public void addSignerKey(PrivateKey privateKey) {
        keysToSignWith.add(privateKey);
    }

    /**
     * Important. This method should be invoked after {@link #check()}.
     *
     * @return true if there are no errors detected by now
     */
    public boolean isOk() {
        return errors.isEmpty();
    }

    public byte[] sealAsV2() {
        byte[] theContract = Boss.pack(
                BossBiMapper.serialize(
                        Binder.of(
                                "contract", this,
                                "revoking", revokingItems.stream()
                                        .map(i -> i.getLastSealedBinary())
                                        .collect(Collectors.toList()),
                                "new", newItems.stream()
                                        .map(i -> i.seal())
                                        .collect(Collectors.toList())
                        )
                )
        );
        //redundand code. already executed here newItems.stream().map(i -> i.seal())
        //newItems.forEach(c -> c.seal());
        Binder result = Binder.of(
                "type", "unicapsule",
                "version", 2,
                "data", theContract
        );
        List<byte[]> signatures = new ArrayList<>();
        keysToSignWith.forEach(key -> {
            signatures.add(ExtendedSignature.sign(key, theContract));
        });
        result.put("data", theContract);
        result.put("signatures", signatures);
        setOwnBinary(result);
        return sealedBinary;
    }

    public byte[] seal() {
        Object forPack = BossBiMapper.serialize(
                Binder.of(
                        "contract", this,
                        "revoking", revokingItems.stream()
                                .map(i -> i.getId())
                                .collect(Collectors.toList()),
                        "new", newItems.stream()
                                .map(i -> i.getId(true))
                                .collect(Collectors.toList())
                )
        );
        byte[] theContract = Boss.pack(
                forPack
        );
        Binder result = Binder.of(
                "type", "unicapsule",
                "version", 3,
                "data", theContract
        );

        List<byte[]> signatures = new ArrayList<>();
        result.put("data", theContract);
        result.put("signatures", signatures);
        setOwnBinary(result);

        addSignatureToSeal(keysToSignWith);

        return sealedBinary;
    }

    public void addSignatureToSeal(PrivateKey privateKey) {
        Set<PrivateKey> keys = new HashSet<>();
        keys.add(privateKey);
        addSignatureToSeal(keys);
    }

    /**
     * Add signature to sealed (before) contract. Do not deserializing or changing contract bytes,
     * but will change sealed and hashId.
     *
     * Useful if you got contracts from third-party (another computer) and need to sign it.
     * F.e. contracts that shoul be sign with two persons.
     *
     * @param privateKeys - key to sign contract will with
     */
    public void addSignatureToSeal(Set<PrivateKey> privateKeys) {
        if (sealedBinary == null)
            throw new IllegalStateException("failed to add signature: sealed binary does not exist");

        Binder data = Boss.unpack(sealedBinary);
        byte[] contractBytes = data.getBinaryOrThrow("data");

        List<byte[]> signatures = data.getListOrThrow("signatures");
        for (PrivateKey key : privateKeys) {
            byte[] signature = ExtendedSignature.sign(key, contractBytes);
            signatures.add(signature);
            data.put("signatures", signatures);

            ExtendedSignature es = ExtendedSignature.verify(key.getPublicKey(), signature, contractBytes);
            if (es != null) {
                sealedByKeys.put(key.getPublicKey(), es);
            }
        }

        setOwnBinary(data);
    }

    public void removeAllSignatures() {
        if (sealedBinary == null)
            throw new IllegalStateException("failed to add signature: sealed binary does not exist");
        Binder data = Boss.unpack(sealedBinary);
        List<byte[]> signatures = new ArrayList<>();
        data.put("signatures", signatures);
        sealedByKeys.clear();

        setOwnBinary(data);
    }

    public boolean findSignatureInSeal(PublicKey publicKey) throws Quantiser.QuantiserException {
        if (sealedBinary == null)
            throw new IllegalStateException("failed to create revision");
        Binder data = Boss.unpack(sealedBinary);
        byte[] contractBytes = data.getBinaryOrThrow("data");
        List<Bytes> signatures = data.getListOrThrow("signatures");
        for (Bytes s : signatures) {
            verifySignatureQuantized(publicKey);
            if (ExtendedSignature.verify(publicKey, s.getData(), contractBytes) != null)
                return true;
        }
        return false;
    }

    public byte[] extractTheContract() {
        if (sealedBinary == null)
            throw new IllegalStateException("failed to create revision");
        Binder data = Boss.unpack(sealedBinary);
        byte[] contractBytes = data.getBinaryOrThrow("data");
        return contractBytes;
    }

    /**
     * Get the last knwon packed representation pf the contract. Should be called if the contract was contructed from a
     * packed binary ({@link #Contract(byte[])} or was explicitly sealed {@link #seal()}.
     * <p>
     * Caution. This method could return out of date binary, if the contract was changed after the {@link #seal()} call.
     * Before we will add track of changes, use it only if you are sure that {@link #seal()} was called and contract was
     * not changed since then.
     *
     * @return last result of {@link #seal()} call, or the binary from which was constructed.
     */
    public byte[] getLastSealedBinary() {
        return sealedBinary;
    }

    private void setOwnBinary(Binder result) {
        sealedBinary = Boss.pack(result);
        transactionPack = null;
        this.id = HashId.of(sealedBinary);
    }

    public Contract createRevision() {
        return createRevision((Transactional)null);
    }

    /**
     * Create new revision to be changed, signed sealed and then ready to approve. Created "revision" contract is a copy
     * of this contract, with all fields and references correctly set. After this call one need to change mutable
     * fields, add signing keys, seal it and then apss to Universa network for approval.
     *
     * @return new revision of this contract, identical to this one, to be modified.
     */
    public synchronized Contract createRevision(Transactional transactional) {
        try {
            // We need deep copy, so, simple while not that fast.
            // note that revisions are create on clients where speed it not of big importance!
            Contract newRevision = copy();
            // modify the deep copy for a new revision
            newRevision.state.revision = state.revision + 1;
            newRevision.state.createdAt = ZonedDateTime.ofInstant(Instant.ofEpochSecond(ZonedDateTime.now().toEpochSecond()), ZoneId.systemDefault());
            newRevision.state.parent = getId();
            newRevision.state.origin = state.revision == 1 ? getId() : state.origin;
            newRevision.revokingItems.add(this);
            newRevision.transactional = transactional;

            return newRevision;
        } catch (Exception e) {
            throw new IllegalStateException("failed to create revision", e);
        }
    }

    public int getRevision() {
        return state.revision;
    }

    public HashId getParent() {
        return state.parent;
    }

    public HashId getRawOrigin() {
        return state.origin;
    }

    public HashId getOrigin() {
        HashId o = state.origin;
        return o == null ? getId() : o;
    }

    public Contract createRevision(PrivateKey... keys) {
        return createRevision(null, keys);
    }

    public Contract createRevision(Transactional transactional, PrivateKey... keys) {
        return createRevision(Do.list(keys), transactional);
    }

    public synchronized Contract createRevision(Collection<PrivateKey> keys) {
        return createRevision(keys, null);
    }

    public synchronized Contract createRevision(Collection<PrivateKey> keys, Transactional transactional) {
        Contract newRevision = createRevision(transactional);
        Set<KeyRecord> krs = new HashSet<>();
        keys.forEach(k -> {
            krs.add(new KeyRecord(k.getPublicKey()));
            newRevision.addSignerKey(k);
        });
        newRevision.setCreator(krs);
        return newRevision;
    }


    public Role setCreator(Collection<KeyRecord> records) {
        return setRole("creator", records);
    }

    public Role setCreator(Role role) {
        return registerRole(role);
    }

    public Role getOwner() {
        return getRole("owner");
    }

    @NonNull
    public Role setOwnerKey(Object keyOrRecord) {
        return setRole("owner", Do.listOf(keyOrRecord));
    }

    @NonNull
    public Role setOwnerKeys(Collection<?> keys) {
        return setRole("owner", keys);
    }

    @NonNull
    public Role setOwnerKeys(PublicKey... keys) {
        return setOwnerKeys(asList(keys));
    }

    @NonNull
    private Role setRole(String name, Collection keys) {
        return registerRole(new SimpleRole(name, keys));
    }

    public Role getCreator() {
        return getRole("creator");
    }

    public Multimap<String, Permission> getPermissions() {
        return permissions;
    }

    public Binder getStateData() {
        return state.getData();
    }

    public Role setIssuerKeys(PublicKey... keys) {
        return setRole("issuer", asList(keys));
    }

    public void setExpiresAt(ZonedDateTime dateTime) {
        state.setExpiresAt(dateTime);
    }

    @Override
    public void deserialize(Binder data, BiDeserializer deserializer) {
        int l = data.getIntOrThrow("api_level");
        if (l > MAX_API_LEVEL)
            throw new RuntimeException("contract api level conflict: found " + l + " my level " + apiLevel);
        deserializer.withContext(this, () -> {

            if (definition == null)
                definition = new Definition();
            definition.deserializeWith(data.getBinderOrThrow("definition"), deserializer);

            if (state == null)
                state = new State();
            state.deserealizeWith(data.getBinderOrThrow("state"), deserializer);

            if (transactional == null)
                transactional = new Transactional();
            transactional.deserializeWith(data.getBinder("transactional", null), deserializer);

        });
    }

    @Override
    public Binder serialize(BiSerializer s) {
        Binder binder = Binder.of(
                        "api_level", apiLevel,
                        "definition", definition.serializeWith(s),
                        "state", state.serializeWith(s)
        );

        if(transactional != null)
            binder.set("transactional", transactional.serializeWith(s));

        return binder;
    }

    /**
     * Split one or more siblings from this revision. This must be a new revision (use {@link
     * #createRevision(PrivateKey...)} first. We recommend setting up signing keys before calling split, otherwise
     * caller must explicitly set signing keys on each contract.
     * <p>
     * It the new revision is already split, it can't be split again.
     * <p>
     * It is important to understand that this revision become a contract that has to be registered with Universa
     * service, which will automatically register all requested siblings in a transaction. Do not register siblings
     * themselves: registering this contract will do all the work.
     *
     * @param count number of siblings to split
     *
     * @return array of just created siblings, to modify their state only.
     */
    public Contract[] split(int count) {
        // we can split only the new revision and only once this time
        if (state.getBranchRevision() == state.revision)
            throw new IllegalArgumentException("this revision is already split");
        if (count < 1)
            throw new IllegalArgumentException("split: count should be > 0");

        // initialize context if not yet
        getContext();

        state.setBranchNumber(0);
        Contract[] results = new Contract[count];
        for (int i = 0; i < count; i++) {
            // we can't create revision as this is already a new revision, so we copy self:
            Contract c = copy();
            // keys are not copied by default
            c.setKeysToSignWith(getKeysToSignWith());
            // save branch information
            c.getState().setBranchNumber(i + 1);
            // and it should refer the same parent to and set of siblings
            c.context = context;
            context.siblings.add(c);
            newItems.add(c);
            results[i] = c;
        }
        return results;
    }

    /**
     * Split this contract extracting specified value from a named field. The contract must have suitable {@link
     * com.icodici.universa.contract.permissions.SplitJoinPermission} and be signed with proper keys to pass checks.
     * <p>
     * Important. This contract must be a new revision: call {@link #createRevision(PrivateKey...)} first.
     *
     * @param fieldName      field to extract from
     * @param valueToExtract how much to extract
     *
     * @return new sibling contract with the extracted value.
     */
    public Contract splitValue(String fieldName, Decimal valueToExtract)  {
        Contract sibling = split(1)[0];
        Binder stateData = getStateData();
        Decimal value = new Decimal(stateData.getStringOrThrow(fieldName));
        stateData.set(fieldName, value.subtract(valueToExtract));
        sibling.getStateData().put(fieldName, valueToExtract.toString());
        return sibling;
    }

    /**
     * If the contract is creating siblings, e.g. contracts with the same origin and parent but different branch ids,
     * this method will return them all. Note that siblings do not include this contract.
     *
     * @return list of siblings to be created together with this contract.
     */
    public Set<Contract> getSiblings() {
        return context.siblings;
    }

    /**
     * Add one or more siblings to the contract. Note that those must be sealed before calling {@link #seal()} or {@link
     * #getPackedTransaction()}. Do not reseal as it changes the id!
     *
     * @param newContracts
     */
    public void addNewItems(Contract... newContracts) {
        for (Contract c : newContracts) {
            newItems.add(c);
        }
    }

    /**
     * Get the named field in 'dotted' notation, e.g. 'state.data.name', or 'state.origin', 'definition.issuer' and so
     * on.
     *
     * @param name
     *
     * @return
     */
    public <T> T get(String name) {
        String originalName = name;
        if (name.startsWith("definition.")) {
            name = name.substring(11);
            switch (name) {
                case "expires_at":
                    return (T) state.expiresAt;
                case "created_at":
                    return (T) definition.createdAt;
                case "issuer":
                    return (T) getRole("issuer");
                case "origin":
                    return (T) getOrigin();
                default:
                    if (name.startsWith("data."))
                        return definition.data.getOrNull(name.substring(5));
            }
        } else if (name.startsWith("state.")) {
            name = name.substring(6);
            switch (name) {
                case "origin":
                    return (T) getOrigin();
                case "created_at":
                    return (T) state.createdAt;
                default:
                    if (name.startsWith("data."))
                        return state.data.getOrNull(name.substring(5));
            }
        } else switch (name) {
            case "id":
                return (T) getId();
            case "origin":
                return (T) getOrigin();
        }
        throw new IllegalArgumentException("bad root: " + originalName);
    }

    /**
     * Set the named field in 'dotted' notation, e.g. 'state.data.name', or 'state.origin', 'definition.issuer' and so
     * on.
     *
     * @param name
     * @param value
     */
    public void set(String name, Binder value) {
        if (name.startsWith("definition.")) {
            name = name.substring(11);
            switch (name) {
                case "expires_at":
                    state.expiresAt = value.getZonedDateTimeOrThrow("data");
                    return;
                case "created_at":
                    definition.createdAt = value.getZonedDateTimeOrThrow("data");
                    return;
                case "issuer":
                    setRole("issuer", ((SimpleRole) value.get("data")).getKeys());
                    return;
//                case "origin":
//                    setOrigin();
//                return;
                default:
                    if (name.startsWith("data."))
                        definition.data.set(name.substring(5), value.getOrThrow("data"));
                    return;
            }
        } else if (name.startsWith("state.")) {
            name = name.substring(6);
            switch (name) {
//                case "origin":
//                    setOrigin();
//                return;
                case "created_at":
                    state.createdAt = value.getZonedDateTimeOrThrow("data");
                    return;
                default:
                    if (name.startsWith("data."))
                        state.data.set(name.substring(5), value.getOrThrow("data"));
                    return;
            }
        } else switch (name) {
//            case "id":
//                setId();
//                return;
//            case "origin":
//                setOrigin();
//            return;
        }
        throw new IllegalArgumentException("bad root: " + name);
    }

    public List<Contract> extractByValidReference(List<Contract> contracts) {
        return contracts.stream()
                .filter(this::isValidReference)
                .collect(Collectors.toList());
    }

    private boolean isValidReference(Contract contract) {
        boolean resultWrap = true;

        List<Reference> referencesList = this.getDefinition().getReferences();

        for (Reference references: referencesList) {
            boolean result = true;

            if (references == null) result = false;

            //check roles
            if (result) {
                List<String> roles = references.getRoles();
                Map<String, Role> contractRoles = contract.getRoles();
                result = roles.stream()
                        .anyMatch(role -> contractRoles.containsKey(role));
            }

            //check origin
            if (result) {
                final HashId origin = references.origin;
                result = (origin == null || !(contract.getOrigin().equals(this.getOrigin())));
            }


            //check fields
            if (result) {
                List<String> fields = references.getFields();
                Binder stateData = contract.getStateData();
                result = fields.stream()
                        .anyMatch(field -> stateData.get(field) != null);
            }

            if (!result)
                resultWrap = false;
        }


        return resultWrap;
    }

    public static Contract fromSealedFile(String contractFileName) throws IOException {
        return new Contract(Do.read(contractFileName), new TransactionPack());
    }

    public ZonedDateTime getIssuedAt() {
        return definition.createdAt;
    }

    /**
     * Get last sealed binary or create it if there is not
     *
     * @param sealAsNeed {@link #seal()} it if there is no cached binary
     *
     * @return sealed contract or null
     */
    public byte[] getLastSealedBinary(boolean sealAsNeed) {
        if (sealedBinary == null && sealAsNeed)
            seal();
        return sealedBinary;
    }

    /**
     * Pack the contract to the most modern .unicon format, same as {@link TransactionPack#pack()}. Uses bounded {@link
     * TransactionPack} instance to save together the contract, revoking and new items (if any). This is a binary format
     * using to submit for approval. Use {@link #fromPackedTransaction(byte[])} to read this format.
     *
     * @return packed binary form.
     */
    public byte[] getPackedTransaction() {
        return getTransactionPack().pack();
    }

    /**
     * Main .unicon read routine. Load any .unicon version and construct a linked Contract with counterparts (new and
     * revoking items if present) and corresponding {@link TransactionPack} instance to pack it to store or send to
     * approval.
     * <p>
     * The supported file variants are:
     * <p>
     * - v2 legacy unicon. Is loaded with packed counterparts if any. Only for compatibility, avoid using it.
     * <p>
     * - v3 compacted unicon. Is loaded without counterparts, should be added later if need with {@link
     * #addNewItems(Contract...)} and {@link #addRevokingItems(Contract...)}. This is a good way to keep the long
     * contract chain.
     * <p>
     * - packed {@link TransactionPack}. This is a preferred way to keep current contract state.
     * <p>
     * To pack and write corresponding .unicon file use {@link #getPackedTransaction()}.
     *
     * @param packedItem some packed from of the universa contract
     *
     * @throws IOException if the packedItem is broken
     */
    public static Contract fromPackedTransaction(@NonNull byte[] packedItem) throws IOException {
        TransactionPack tp = TransactionPack.unpack(packedItem);
        return tp.getContract();
    }

    public void setTransactionPack(TransactionPack transactionPack) {
        this.transactionPack = transactionPack;
    }

    public synchronized TransactionPack getTransactionPack() {
        if (transactionPack == null)
            transactionPack = new TransactionPack(this);
        return transactionPack;
    }

    /**
     * Create revocation contract. To revoke the contract it is necessary that it has "revoke" permission, and one need
     * the keys to be able to play the role assigned to it.
     * <p>
     * So, to revoke some contract:
     * <p>
     * - call {@link #createRevocation(PrivateKey...)} with key or keys that can play the role for "revoke" permission
     * <p>
     * - register it in the Universa network, see {@link com.icodici.universa.node2.network.Client#register(byte[], long)}.
     * Upon the successful registration the source contract will be revoked. Use transaction contract's {@link
     * #getPackedTransaction()} to obtain a binary to submit to the client.
     *
     * @param keys one or more keys that together can play the role assigned to the revoke permission.
     *
     * @return ready sealed contract that revokes this contract on registration
     */
    public Contract createRevocation(PrivateKey... keys) {
        return ContractsService.createRevocation(this, keys);
    }

    public List<Contract> getRevoking() {
        return new ArrayList<Contract>((Collection) getRevokingItems());
    }

    public List<? extends Contract> getNew() {
        return new ArrayList<Contract>((Collection) getNewItems());
    }

    /**
     * @param keys that should be tested
     *
     * @return true if the set of keys is enough revoke this contract.
     */
    public boolean canBeRevoked(Set<PublicKey> keys) throws Quantiser.QuantiserException {
        for (Permission perm : permissions.getList("revoke")) {
            if (perm.isAllowedForKeys(keys)){
                checkApplicablePermissionQuantized(perm);
                return true;
            }
        }
        return false;
    }


    public Transactional createTransactionalSection() {
        transactional = new Transactional();
        return transactional;
    }

    // processes that should be quantized

    /**
     * Verify signature, but before quantize this operation.
     * @param key
     * @throws Quantiser.QuantiserException
     */
    protected void verifySignatureQuantized(PublicKey key) throws Quantiser.QuantiserException {
        // Add check signature quanta
        if(key.getBitStrength() == 2048) {
            quantiser.addWorkCost(Quantiser.QuantiserProcesses.PRICE_CHECK_2048_SIG);
        } else {
            quantiser.addWorkCost(Quantiser.QuantiserProcesses.PRICE_CHECK_4096_SIG);
        }
    }


    /**
     * Quantize given permission (add cost for that permission).
     * Use for permissions that will be applicated, but before checking.
     * @param permission
     * @throws Quantiser.QuantiserException
     */
    public void checkApplicablePermissionQuantized(Permission permission) throws Quantiser.QuantiserException {
        // Add check an applicable permission quanta
        quantiser.addWorkCost(Quantiser.QuantiserProcesses.PRICE_APPLICABLE_PERM);

        // Add check a splitjoin permission	in addition to the permission check quanta
        if(permission instanceof SplitJoinPermission) {
            quantiser.addWorkCost(Quantiser.QuantiserProcesses.PRICE_SPLITJOIN_PERM);
        }
    }


    protected void checkSubItemQuantized(Contract contract) throws Quantiser.QuantiserException {
        // Add checks from subItem quanta
        checkSubItemQuantized(contract, "");
    }


    protected void checkSubItemQuantized(Contract contract, String prefix) throws Quantiser.QuantiserException {
        checkSubItemQuantized(contract, prefix, null);
    }


    protected void checkSubItemQuantized(Contract contract, String prefix, List<Contract> neighbourContracts) throws Quantiser.QuantiserException {
        // Add checks from subItem quanta
        contract.quantiser.reset(quantiser.getQuantaLimit() - quantiser.getQuantaSum());
        contract.check(prefix, neighbourContracts);
        quantiser.addWorkCostFrom(contract.quantiser);
    }


    public class State {
        private int revision;
        private Binder state;
        private ZonedDateTime createdAt;
        private ZonedDateTime expiresAt;
        private HashId origin;
        private HashId parent;
        private Binder data;
        private String branchId;

        private State() {
            createdAt = definition.createdAt;
            revision = 1;
        }

        public void setExpiresAt(ZonedDateTime expiresAt) {
            this.expiresAt = expiresAt;
        }


        private State initializeWithDsl(Binder state) {
            this.state = state;
            createdAt = state.getZonedDateTime("created_at", null);
            expiresAt = state.getZonedDateTime("expires_at", null);
            revision = state.getIntOrThrow("revision");
            data = state.getOrCreateBinder("data");
            if (createdAt == null) {
                if (revision != 1)
                    throw new IllegalArgumentException("state.created_at must be set for revisions > 1");
                createdAt = definition.createdAt;
            }
            createRole("owner", state.get("owner"));
            createRole("creator", state.getOrThrow("created_by"));
            return this;
        }

        public int getRevision() {
            return revision;
        }

        public ZonedDateTime getCreatedAt() {
            return createdAt;
        }

        public Binder serializeWith(BiSerializer serializer) {

            Binder of = Binder.of(
                    "created_at", createdAt,
                    "revision", revision,
                    "owner", getRole("owner"),
                    "created_by", getRole("creator"),
                    "branch_id", branchId,
                    "origin", serializer.serialize(origin),
                    "parent", serializer.serialize(parent),
                    "data", data
            );

            if (expiresAt != null)
                of.set("expires_at", expiresAt);

            return serializer.serialize(
                    of
            );
        }

        public Binder getData() {
            return data;
        }

        public void deserealizeWith(Binder data, BiDeserializer d) {
            createdAt = data.getZonedDateTimeOrThrow("created_at");
            expiresAt = data.getZonedDateTime("expires_at", null);

            revision = data.getIntOrThrow("revision");

            if (revision <= 0)
                throw new IllegalArgumentException("illegal revision number: " + revision);
            Role r = registerRole(d.deserialize(data.getBinderOrThrow("owner")));
            if (!r.getName().equals("owner"))
                throw new IllegalArgumentException("bad owner role name");
            r = registerRole(d.deserialize(data.getBinderOrThrow("created_by")));
            if (!r.getName().equals("creator"))
                throw new IllegalArgumentException("bad creator role name");
            this.data = data.getBinder("data", Binder.EMPTY);
            branchId = data.getString("branch_id", null);
            parent = d.deserialize(data.get("parent"));
            origin = d.deserialize(data.get("origin"));
        }

        private Integer branchRevision = null;

        /**
         * Revision at which this branch was splitted
         *
         * @return
         */
        public Integer getBranchRevision() {
            if (branchRevision == null) {
                if (branchId == null)
                    branchRevision = 0;
                else
                    // we usually don't need sibling number here
                    branchRevision = Integer.valueOf(branchId.split(":")[0]);
            }
            return branchRevision;
        }

        public String getBranchId() {
            return branchId;
        }

        public void setBranchNumber(int number) {
            branchId = revision + ":" + number;
            branchRevision = number;
        }
    }

    private Multimap<String, Permission> permissions = new Multimap<>();

    public class Definition {

        private ZonedDateTime createdAt;

        public void setExpiresAt(ZonedDateTime expiresAt) {
            this.expiresAt = expiresAt;
        }

        public void setData(Binder data) {
            this.data = data;
        }

        private ZonedDateTime expiresAt;
        private Binder definition;
        private Binder data;
        private List<Reference> references = new ArrayList<>();


        private Definition() {
            createdAt = ZonedDateTime.now();
        }

        private Definition initializeWithDsl(Binder definition) {
            this.definition = definition;
            Role issuer = createRole("issuer", definition.getOrThrow("issuer"));
            createdAt = definition.getZonedDateTimeOrThrow("created_at");
            Object t = definition.getOrDefault("expires_at", null);
            if (t != null)
                expiresAt = decodeDslTime(t);
            registerRole(issuer);
            data = definition.getBinder("data");
            return this;
        }

        public List<Reference> getReferences() {
            return this.references;
        }

        /**
         * Collect all permissions and create links to roles or new roles as appropriate
         */
        private void scanDslPermissions() {
            definition.getBinderOrThrow("permissions").forEach((name, params) -> {
                // this complex logic is needed to process both yaml-imported structures
                // and regular serialized data in the same place
                if (params instanceof Object[])
                    for (Object x : (Object[]) params)
                        loadDslPermission(name, x);
                else if (params instanceof List)
                    for (Object x : (List) params)
                        loadDslPermission(name, x);
                else if (params instanceof Permission)
                    addPermission((Permission) params);
                else
                    loadDslPermission(name, params);
            });
        }

        private void loadDslPermission(String name, Object params) {
            String roleName = null;
            Role role = null;
            Binder binderParams = null;
            if (params instanceof CharSequence)
                // yaml style: permission: role
                roleName = params.toString();
            else {
                // extended yaml style or serialized object
                binderParams = Binder.from(params);
                Object x = binderParams.getOrThrow("role");
                if (x instanceof Role)
                    // serialized, role object
                    role = registerRole((Role) x);
                else
                    // yaml, extended form: permission: { role: name, ... }
                    roleName = x.toString();
            }
            if (role == null && roleName != null) {
                // we need to create alias to existing role
                role = createRole("@" + name, roleName);
            }
            if (role == null)
                throw new IllegalArgumentException("permission " + name + " refers to missing role: " + roleName);
            // now we have ready role and probably parameter for custom rights creation
            addPermission(Permission.forName(name, role, params instanceof String ? null : binderParams));
        }

        public Binder getData() {
            if (data == null)
                data = new Binder();
            return data;
        }

        public Binder serializeWith(BiSerializer serializer) {
            List<Permission> pp = permissions.values();
            Binder pb = new Binder();
            int lastId = 0;

            // serialize permissions with a valid id
            permissions.values().forEach(perm -> {
                String pid = perm.getId();
                if (pid == null)
                    throw new IllegalStateException("permission without id: " + perm);
                if (pb.containsKey(pid))
                    throw new IllegalStateException("permission: duplicate permission id found: " + perm);
                pb.put(pid, perm);
            });

            Collections.sort(pp);
            Binder of = Binder.of(
                    "issuer", getIssuer(),
                    "created_at", createdAt,
                    "data", data,
                    "permissions", pb
            );

            if (expiresAt != null)
                of.set("expires_at", expiresAt);

            if (references != null)
                of.set("references", references);


            return serializer.serialize(
                    of
            );
        }

        public void deserializeWith(Binder data, BiDeserializer d) {
            registerRole(d.deserialize(data.getBinderOrThrow("issuer")));
            createdAt = data.getZonedDateTimeOrThrow("created_at");
            expiresAt = data.getZonedDateTime("expires_at", null);
            this.data = d.deserialize(data.getBinder("data", Binder.EMPTY));
            this.references = d.deserialize(data.getList("references", null));
            Map<String, Permission> perms = d.deserialize(data.getOrThrow("permissions"));
            perms.forEach((id, perm) -> {
                perm.setId(id);
                addPermission(perm);
            });
        }

    }

    /**
     * This section of a contract need for complex contracts, that consist of some contracts and need to be register all or no one.
     * F.e. contract that has contracts in revoking or new items and new item shouldn't be registered separately.
     * To do it, add transactional section with references to another contracts or their transactional sections.
     * And that contracts will can be registered only together.
     *
     * Transactional lives only one revision, so if you created new revision from contract, section is became null.
     *
     * Section does not check for allowed modification.
     */
    public class Transactional {

        private String id;
        private List<Reference> references;

        private Transactional() {

        }

        public Binder serializeWith(BiSerializer serializer) {

            Binder b = Binder.of(
                    "id", id
            );

            if (references != null)
                b.set("references", serializer.serialize(references));

            return serializer.serialize(b);
        }

        public void deserializeWith(Binder data, BiDeserializer d) {
            if(data != null) {
                id = data.getString("id", null);
                List refs = data.getList("references", null);
                if(refs != null) {
                    references = d.deserializeCollection(refs);
                }
            }
        }

        public void addReference(Reference reference) {
            if(references == null) {
                references = new ArrayList<>();
            }

            references.add(reference);
        }

        public List<Reference> getReferences() {
            return references;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }
    }

    public void traceErrors() {
        errors.forEach(e -> {
            System.out.println("Error: " + e);
        });
    }

    public String getErrorsString() {
        return errors.stream().map(Object::toString).collect(Collectors.joining(","));
    }

    @Override
    protected Object clone() throws CloneNotSupportedException {
        return copy();
    }

    /**
     * Make a valid deep copy of a contract
     *
     * @return
     */
    public synchronized Contract copy() {
        return Boss.load(Boss.dump(this));
    }

    protected Context getContext() {
        if (context == null) {
            context = new Context(getRevokingItem(getParent()));
            context.siblings.add(this);
            newItems.forEach(i -> {
                if (i.getParent() != null && i.getParent().equals(getParent()))
                    context.siblings.add(i);
            });

        }
        return context;
    }

    @Override
    public boolean isTU(PublicKey issuerKey) {
        if (!getIssuer().getKeys().equals(new HashSet<>(Arrays.asList(issuerKey))))
            return false;
        return true;
    }

    /**
     * Transaction context. Holds temporary information about a context transaction relevant to create sibling, e.g.
     * contract splitting. Allow new items being created to get the base contract (that is creating) and get the full
     * list of siblings.
     */
    protected class Context {
        private final Set<Contract> siblings = new HashSet<>();
        private final Contract base;

        public Context(@NonNull Contract base) {
            this.base = base;
        }
    }

    final public class ContractDev {

        private Contract c;

        public ContractDev(Contract c) throws Exception {
            this.c = c;
        }

        public void setOrigin(HashId origin) {
            this.c.getState().origin = origin;
        }

        public void setParent(HashId parent) {
            this.c.getState().parent = parent;
        }

        public Contract getContract() {
            return this.c;
        }
    }

    static private Pattern relativeTimePattern = Pattern.compile(
            "(\\d+) (hour|min|day)\\w*$",
            Pattern.CASE_INSENSITIVE);

    static public ZonedDateTime decodeDslTime(Object t) {
        if (t instanceof ZonedDateTime)
            return (ZonedDateTime) t;
        if (t instanceof CharSequence) {
            if (t.equals("now()"))
                return ZonedDateTime.now();
            Matcher m = relativeTimePattern.matcher((CharSequence) t);
            System.out.println("MATCH: " + m);
            if (m.find()) {
                ZonedDateTime now = ZonedDateTime.now();
                int amount = Integer.valueOf(m.group(1));
                String unit = m.group(2);
                switch (unit) {
                    case "min":
                        return now.plusMinutes(amount);
                    case "hour":
                        return now.plusHours(amount);
                    case "day":
                        return now.plusDays(amount);
                    default:
                        throw new IllegalArgumentException("unknown time unit: " + unit);

                }
            }
        }
        throw new IllegalArgumentException("can't convert to datetime: "+t);
    }

    static {
        Config.forceInit(ItemResult.class);
        Config.forceInit(HashId.class);
        Config.forceInit(Contract.class);
        Config.forceInit(Permission.class);
        Config.forceInit(Contract.class);
        Config.forceInit(ChangeNumberPermission.class);
        Config.forceInit(ChangeOwnerPermission.class);
        Config.forceInit(SplitJoinPermission.class);
        Config.forceInit(PublicKey.class);
        Config.forceInit(PrivateKey.class);
        Config.forceInit(KeyRecord.class);

        DefaultBiMapper.registerClass(Contract.class);
        DefaultBiMapper.registerClass(ChangeNumberPermission.class);
        DefaultBiMapper.registerClass(ChangeOwnerPermission.class);
        DefaultBiMapper.registerClass(ModifyDataPermission.class);
        DefaultBiMapper.registerClass(RevokePermission.class);
        DefaultBiMapper.registerClass(SplitJoinPermission.class);
        // roles
        DefaultBiMapper.registerClass(ListRole.class);
        DefaultBiMapper.registerClass(Role.class);
        DefaultBiMapper.registerClass(RoleLink.class);
        DefaultBiMapper.registerClass(SimpleRole.class);
        // other
        DefaultBiMapper.registerClass(KeyRecord.class);
        DefaultBiMapper.registerAdapter(PublicKey.class, PUBLIC_KEY_BI_ADAPTER);
        DefaultBiMapper.registerClass(Reference.class);

        DefaultBiMapper.registerClass(Permission.class);
    }

}
