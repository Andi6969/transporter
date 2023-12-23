package ml.volder.transporter.modules;

import ml.volder.transporter.TransporterAddon;
import ml.volder.transporter.classes.items.Item;
import ml.volder.transporter.classes.items.ItemManager;
import ml.volder.transporter.gui.TransporterModulesMenu;
import ml.volder.unikapi.UnikAPI;
import ml.volder.unikapi.api.input.InputAPI;
import ml.volder.unikapi.api.inventory.InventoryAPI;
import ml.volder.unikapi.api.player.PlayerAPI;
import ml.volder.unikapi.event.EventHandler;
import ml.volder.unikapi.event.EventManager;
import ml.volder.unikapi.event.Listener;
import ml.volder.unikapi.event.events.clientkeypressevent.ClientKeyPressEvent;
import ml.volder.unikapi.event.events.clientmessageevent.ClientMessageEvent;
import ml.volder.unikapi.event.events.clienttickevent.ClientTickEvent;
import ml.volder.unikapi.event.events.serverswitchevent.ServerSwitchEvent;
import ml.volder.unikapi.guisystem.ModTextures;
import ml.volder.unikapi.guisystem.elements.*;
import ml.volder.unikapi.keysystem.Key;
import ml.volder.unikapi.logger.Logger;
import ml.volder.unikapi.types.Material;

import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class BalanceModule extends SimpleModule implements Listener {
    private TransporterAddon addon;

    public BalanceModule(ModuleManager.ModuleInfo moduleInfo) {
        super(moduleInfo);
        this.addon = TransporterAddon.getInstance();
    }

    @Override
    public SimpleModule init() {
        return this;
    }

    @Override
    public SimpleModule enable() {
        EventManager.registerEvents(this);
        return this;
    }

    @Override
    public void fillSettings(Settings subSettings) {
        HeaderElement headerElement = new HeaderElement("Vælg de servere hvor din balance skal opdateres!");
        subSettings.add(headerElement);

        BooleanElement booleanElement = new BooleanElement(
                "Opdatere ved join",
                getDataManager(),
                "updateAtJoin",
                new ControlElement.IconData(Material.DIODE),
                true
        );
        updateOnJoin = booleanElement.getCurrentValue();
        booleanElement.addCallback(b -> updateOnJoin = b);
        subSettings.add(booleanElement);

        BooleanElement booleanElement2 = new BooleanElement(
                "Opdatere i interval",
                getDataManager(),
                "updateInterval",
                new ControlElement.IconData(Material.DIODE),
                false
        );
        updateInterval = booleanElement2.getCurrentValue();
        booleanElement2.addCallback(b -> updateInterval = b);
        subSettings.add(booleanElement2);

        NumberElement numberElement = new NumberElement(
                "Update Interval (Sekunder)", getDataManager(),
                "intervalUpdate",
                new ControlElement.IconData(Material.REDSTONE_TORCH),
                60
        );
        numberElement.setMinValue(10);
        updateIntervalSeconds = numberElement.getCurrentValue();
        numberElement.addCallback(i -> updateIntervalSeconds = i);
        subSettings.add(numberElement);
    }

    @Override
    public void loadConfig() {
        super.loadConfig();
        balance = hasConfigEntry("currentBalance") ? getDataManager().getSettings().getData().get("currentBalance").getAsBigDecimal() : BigDecimal.valueOf(0);
        loadMessageRegexFromCsv();
    }

    private final Map<String, ACTION>  messagesMap = new HashMap<>();


    private void loadMessageRegexFromCsv() {
        messagesMap.clear();
        InputStream inputStream = ItemManager.class.getClassLoader().getResourceAsStream("transporter-balance-messages.csv");
        try (BufferedReader br = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line = br.readLine();
            while (line != null) {
                if(line.startsWith("message_regex"))
                    line = br.readLine();

                try {
                    String[] attributes = line.split(";");
                    messagesMap.put(attributes[0], ACTION.valueOf(attributes[1]));
                    line = br.readLine();
                }catch (Exception e) {
                    UnikAPI.LOGGER.printStackTrace(Logger.LOG_LEVEL.INFO, e);
                    UnikAPI.LOGGER.info("Failed to load message: " + line);
                    line = br.readLine();
                }
            }
        } catch (IOException e) {
            UnikAPI.LOGGER.printStackTrace(Logger.LOG_LEVEL.INFO, e);
            throw new RuntimeException(e);
        }
    }

    public boolean isFeatureActive() {
        return isFeatureActive && ModuleManager.getInstance().getModule(ServerModule.class).isFeatureActive();
    }

    private boolean updateOnJoin = true;
    private boolean updateInterval = false;
    private boolean cancelNextBalanceCommand = false;
    private int updateIntervalSeconds = 60;

    private BigDecimal balance = BigDecimal.valueOf(0);

    @EventHandler
    public void onMessage(ClientMessageEvent event) {
        if (!isFeatureActive() || !TransporterAddon.isEnabled())
            return;
        if(matchBalCommandMessage(event.getCleanMessage()))
            event.setCancelled(true);
        matchMessage(event.getCleanMessage());
    }

    private void matchMessage(String clean) {
        for (Map.Entry<String, ACTION> entry : messagesMap.entrySet()) {
            String regex = entry.getKey();
            ACTION action = entry.getValue();
            final Pattern pattern = Pattern.compile(regex);
            final Matcher matcher = pattern.matcher(clean);
            if (matcher.find()) {
                BigDecimal amount = null;
                if (hasGroup(matcher, "amountf1")) {
                    amount = new BigDecimal(matcher.group("amountf1").replace(".", "").replace(",","."));
                }else if (hasGroup(matcher, "amountf2")) {
                    amount = new BigDecimal(matcher.group("amountf2"));
                }
                switch (action) {
                    case ADD:
                        updateBalance(balance.add(amount));
                        break;
                    case REMOVE:
                        updateBalance(balance.subtract(amount));
                        break;
                    case SET:
                        updateBalance(amount);
                        break;
                }
            }
        }
    }

    private boolean hasGroup(Matcher matcher, String string) {
        try {
            matcher.group(string);
            return true;
        }catch (Exception ignored) {
            return false;
        }
    }


    private boolean matchBalCommandMessage(String clean) {
        final Pattern pattern = Pattern.compile("^\\[Money] Balance: ([0-9,.]+) Emeralds$");
        final Matcher matcher = pattern.matcher(clean);
        if (matcher.find()) {
            if (cancelNextBalanceCommand) {
                cancelNextBalanceCommand = false;
                return true;
            }
        }
        return false;
    }


    @EventHandler
    public void onServerSwitch(ServerSwitchEvent event) {
        if (!isFeatureActive() || !updateOnJoin || !TransporterAddon.isEnabled())
            return;
        if(event.getSwitchType() == ServerSwitchEvent.SWITCH_TYPE.LEAVE)
            return;
        new Timer("updateBalance").schedule(new TimerTask() {
            @Override
            public void run() {
                if(ModuleManager.getInstance().getModule(ServerModule.class).getCurrentServer() == null)
                    return;
                if(TransporterAddon.getInstance().getServerList().contains(ModuleManager.getInstance().getModule(ServerModule.class).getCurrentServer())) {
                    cancelNextBalanceCommand = true;
                    PlayerAPI.getAPI().sendCommand("bal");
                }
            }
        }, 1000L);
        new Timer("updateCancelVar").schedule(new TimerTask() {
            @Override
            public void run() {
                cancelNextBalanceCommand = false;
            }
        }, 1500L);
    }

    private int timer = 0;
    @EventHandler
    public void onTick(ClientTickEvent event) {
        if (!isFeatureActive() || !updateInterval)
            return;
        timer+=1;
        if(timer/20 >= updateIntervalSeconds) {
            if(TransporterAddon.getInstance().getServerList().contains(ModuleManager.getInstance().getModule(ServerModule.class).getCurrentServer())){
                cancelNextBalanceCommand = true;
                PlayerAPI.getAPI().sendCommand("bal");
            }
            timer = 0;
        }
    }

    private void updateBalance(BigDecimal newBalance) {
        this.balance = newBalance;
        getDataManager().getSettings().getData().addProperty("currentBalance", balance);
        getDataManager().save();
    }

    public BigDecimal getBalance() {
        return balance;
    }

    private enum ACTION {
        ADD,
        REMOVE,
        SET
    }
}
