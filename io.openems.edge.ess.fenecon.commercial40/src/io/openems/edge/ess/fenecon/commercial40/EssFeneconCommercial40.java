package io.openems.edge.ess.fenecon.commercial40;

import java.time.LocalDateTime;
import java.util.Optional;

import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventConstants;
import org.osgi.service.event.EventHandler;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.exceptions.OpenemsException;
import io.openems.common.types.OpenemsType;
import io.openems.edge.bridge.modbus.api.AbstractOpenemsModbusComponent;
import io.openems.edge.bridge.modbus.api.BridgeModbus;
import io.openems.edge.bridge.modbus.api.ElementToChannelConverter;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.bridge.modbus.api.element.DummyRegisterElement;
import io.openems.edge.bridge.modbus.api.element.SignedWordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedWordElement;
import io.openems.edge.bridge.modbus.api.element.WordOrder;
import io.openems.edge.bridge.modbus.api.task.FC16WriteRegistersTask;
import io.openems.edge.bridge.modbus.api.task.FC3ReadRegistersTask;
import io.openems.edge.common.channel.EnumWriteChannel;
import io.openems.edge.common.channel.IntegerReadChannel;
import io.openems.edge.common.channel.IntegerWriteChannel;
import io.openems.edge.common.channel.doc.Doc;
import io.openems.edge.common.channel.doc.Level;
import io.openems.edge.common.channel.doc.Unit;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.event.EdgeEventConstants;
import io.openems.edge.common.modbusslave.ModbusSlave;
import io.openems.edge.common.modbusslave.ModbusSlaveTable;
import io.openems.edge.common.sum.GridMode;
import io.openems.edge.common.taskmanager.Priority;
import io.openems.edge.common.type.TypeUtils;
import io.openems.edge.ess.api.ManagedSymmetricEss;
import io.openems.edge.ess.api.SymmetricEss;
import io.openems.edge.ess.power.api.Constraint;
import io.openems.edge.ess.power.api.Phase;
import io.openems.edge.ess.power.api.Power;
import io.openems.edge.ess.power.api.Pwr;
import io.openems.edge.ess.power.api.Relationship;

@Designate(ocd = Config.class, factory = true)
@Component( //
		name = "Ess.Fenecon.Commercial40", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE, //
		property = EventConstants.EVENT_TOPIC + "=" + EdgeEventConstants.TOPIC_CYCLE_BEFORE_CONTROLLERS //
)
public class EssFeneconCommercial40 extends AbstractOpenemsModbusComponent
		implements ManagedSymmetricEss, SymmetricEss, OpenemsComponent, EventHandler, ModbusSlave {

	private final Logger log = LoggerFactory.getLogger(EssFeneconCommercial40.class);

	protected final static int MAX_APPARENT_POWER = 40000;

	private final static int UNIT_ID = 100;
	private final static int MIN_REACTIVE_POWER = -10000;
	private final static int MAX_REACTIVE_POWER = 10000;

	private String modbusBridgeId;

	@Reference
	private Power power;

	@Reference
	protected ConfigurationAdmin cm;

	public EssFeneconCommercial40() {
		Utils.initializeChannels(this).forEach(channel -> this.addChannel(channel));
	}

	@Override
	public void applyPower(int activePower, int reactivePower) throws OpenemsException {
		IntegerWriteChannel setActivePowerChannel = this.channel(ChannelId.SET_ACTIVE_POWER);
		setActivePowerChannel.setNextWriteValue(activePower);
		IntegerWriteChannel setReactivePowerChannel = this.channel(ChannelId.SET_REACTIVE_POWER);
		setReactivePowerChannel.setNextWriteValue(reactivePower);
	}

	@Reference(policy = ReferencePolicy.STATIC, policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.MANDATORY)
	protected void setModbus(BridgeModbus modbus) {
		super.setModbus(modbus);
	}

	@Activate
	void activate(ComponentContext context, Config config) {
		super.activate(context, config.id(), config.enabled(), UNIT_ID, this.cm, "Modbus", config.modbus_id());
		this.modbusBridgeId = config.modbus_id();
	}

	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	public String getModbusBridgeId() {
		return modbusBridgeId;
	}

	public enum ChannelId implements io.openems.edge.common.channel.doc.ChannelId {
		ORIGINAL_ALLOWED_CHARGE_POWER(new Doc() //
				.onInit(channel -> { //
					// on each Update to the channel -> set the ALLOWED_CHARGE_POWER value with a
					// delta of max 500
					((IntegerReadChannel) channel).onChange(originalValueChannel -> {
						IntegerReadChannel currentValueChannel = channel.getComponent()
								.channel(ManagedSymmetricEss.ChannelId.ALLOWED_CHARGE_POWER);
						Optional<Integer> originalValue = originalValueChannel.asOptional();
						Optional<Integer> currentValue = currentValueChannel.value().asOptional();
						int value;
						if (!originalValue.isPresent() && !currentValue.isPresent()) {
							value = 0;
						} else if (originalValue.isPresent() && !currentValue.isPresent()) {
							value = originalValue.get();
						} else if (!originalValue.isPresent() && currentValue.isPresent()) {
							value = currentValue.get();
						} else {
							value = Math.max(originalValue.get(), currentValue.get() - 500);
						}
						currentValueChannel.setNextValue(value);
					});
				})), //
		ORIGINAL_ALLOWED_DISCHARGE_POWER(new Doc() //
				.onInit(channel -> { //
					// on each Update to the channel -> set the ALLOWED_DISCHARGE_POWER value with a
					// delta of max 500
					((IntegerReadChannel) channel).onChange(originalValueChannel -> {
						IntegerReadChannel currentValueChannel = channel.getComponent()
								.channel(ManagedSymmetricEss.ChannelId.ALLOWED_DISCHARGE_POWER);
						Optional<Integer> originalValue = originalValueChannel.asOptional();
						Optional<Integer> currentValue = currentValueChannel.value().asOptional();
						int value;
						if (!originalValue.isPresent() && !currentValue.isPresent()) {
							value = 0;
						} else if (originalValue.isPresent() && !currentValue.isPresent()) {
							value = originalValue.get();
						} else if (!originalValue.isPresent() && currentValue.isPresent()) {
							value = currentValue.get();
						} else {
							value = Math.min(originalValue.get(), currentValue.get() + 500);
						}
						currentValueChannel.setNextValue(value);
					});
				})), //
		SYSTEM_STATE(new Doc().options(SystemState.values())), //
		CONTROL_MODE(new Doc().options(ControlMode.values())), //
		BATTERY_MAINTENANCE_STATE(new Doc().options(BatteryMaintenanceState.values())), //
		INVERTER_STATE(new Doc().options(InverterState.values())), //
		PROTOCOL_VERSION(new Doc()), //
		SYSTEM_MANUFACTURER(new Doc().options(SystemManufacturer.values())), //
		SYSTEM_TYPE(new Doc().options(SystemType.values())), //
		BATTERY_STRING_SWITCH_STATE(new Doc().options(BatteryStringSwitchState.values())), //
		BATTERY_VOLTAGE(new Doc().unit(Unit.MILLIVOLT)), //
		BATTERY_CURRENT(new Doc().unit(Unit.MILLIAMPERE)), //
		BATTERY_POWER(new Doc().unit(Unit.WATT)), //
		AC_CHARGE_ENERGY(new Doc().unit(Unit.WATT_HOURS)), //
		AC_DISCHARGE_ENERGY(new Doc().unit(Unit.WATT_HOURS)), //
		GRID_ACTIVE_POWER(new Doc().unit(Unit.WATT)), //
		APPARENT_POWER(new Doc().unit(Unit.VOLT_AMPERE)), //
		CURRENT_L1(new Doc().unit(Unit.MILLIAMPERE)), //
		CURRENT_L2(new Doc().unit(Unit.MILLIAMPERE)), //
		CURRENT_L3(new Doc().unit(Unit.MILLIAMPERE)), //
		VOLTAGE_L1(new Doc().unit(Unit.MILLIVOLT)), //
		VOLTAGE_L2(new Doc().unit(Unit.MILLIVOLT)), //
		VOLTAGE_L3(new Doc().unit(Unit.MILLIVOLT)), //
		FREQUENCY(new Doc().unit(Unit.MILLIHERTZ)), //
		INVERTER_VOLTAGE_L1(new Doc().unit(Unit.MILLIVOLT)), //
		INVERTER_VOLTAGE_L2(new Doc().unit(Unit.MILLIVOLT)), //
		INVERTER_VOLTAGE_L3(new Doc().unit(Unit.MILLIVOLT)), //
		INVERTER_CURRENT_L1(new Doc().unit(Unit.MILLIAMPERE)), //
		INVERTER_CURRENT_L2(new Doc().unit(Unit.MILLIAMPERE)), //
		INVERTER_CURRENT_L3(new Doc().unit(Unit.MILLIAMPERE)), //
		IPM_TEMPERATURE_L1(new Doc().unit(Unit.DEGREE_CELSIUS)), //
		IPM_TEMPERATURE_L2(new Doc().unit(Unit.DEGREE_CELSIUS)), //
		IPM_TEMPERATURE_L3(new Doc().unit(Unit.DEGREE_CELSIUS)), //
		TRANSFORMER_TEMPERATURE_L2(new Doc().unit(Unit.DEGREE_CELSIUS)), //
		SET_WORK_STATE(new Doc().options(SetWorkState.values())), //
		SET_ACTIVE_POWER(new Doc().unit(Unit.WATT)), //
		SET_REACTIVE_POWER(new Doc().unit(Unit.VOLT_AMPERE_REACTIVE)), //
		SET_PV_POWER_LIMIT(new Doc().unit(Unit.WATT)), //
		BMS_DCDC_WORK_STATE(new Doc().options(BmsDcdcWorkState.values())), //
		BMS_DCDC_WORK_MODE(new Doc().options(BmsDcdcWorkMode.values())), //
		STATE_0(new Doc().level(Level.WARNING).text("Emergency Stop")), //
		STATE_1(new Doc().level(Level.WARNING).text("Key Manual Stop")), //
		STATE_2(new Doc().level(Level.WARNING).text("Transformer Phase B Temperature Sensor Invalidation")), //
		STATE_3(new Doc().level(Level.WARNING).text("SD Memory Card Invalidation")), //
		STATE_4(new Doc().level(Level.WARNING).text("Inverter Communication Abnormity")), //
		STATE_5(new Doc().level(Level.WARNING).text("Battery Stack Communication Abnormity")), //
		STATE_6(new Doc().level(Level.WARNING).text("Multifunctional Ammeter Communication Abnormity")), //
		STATE_7(new Doc().level(Level.WARNING).text("Remote Communication Abnormity")), //
		STATE_8(new Doc().level(Level.WARNING).text("PVDC1 Communication Abnormity")), //
		STATE_9(new Doc().level(Level.WARNING).text("PVDC2 Communication Abnormity")), //
		STATE_10(new Doc().level(Level.WARNING).text("Transformer Severe Overtemperature")), //
		STATE_11(new Doc().level(Level.FAULT).text("DC Precharge Contactor Close Unsuccessfully")), //
		STATE_12(new Doc().level(Level.FAULT).text("AC Precharge Contactor Close Unsuccessfully")), //
		STATE_13(new Doc().level(Level.FAULT).text("AC Main Contactor Close Unsuccessfully")), //
		STATE_14(new Doc().level(Level.FAULT).text("DC Electrical Breaker1 Close Unsuccessfully")), //
		STATE_15(new Doc().level(Level.FAULT).text("DC Main Contactor Close Unsuccessfully")), //
		STATE_16(new Doc().level(Level.FAULT).text("AC Breaker Trip")), //
		STATE_17(new Doc().level(Level.FAULT).text("AC Main Contactor Open When Running")), //
		STATE_18(new Doc().level(Level.FAULT).text("DC Main Contactor Open When Running")), //
		STATE_19(new Doc().level(Level.FAULT).text("AC Main Contactor Open Unsuccessfully")), //
		STATE_20(new Doc().level(Level.FAULT).text("DC Electrical Breaker1 Open Unsuccessfully")), //
		STATE_21(new Doc().level(Level.FAULT).text("DC Main Contactor Open Unsuccessfully")), //
		STATE_22(new Doc().level(Level.FAULT).text("Hardware PDP Fault")), //
		STATE_23(new Doc().level(Level.FAULT).text("Master Stop Suddenly")), //
		STATE_24(new Doc().level(Level.FAULT).text("DCShortCircuitProtection")), //
		STATE_25(new Doc().level(Level.FAULT).text("DCOvervoltageProtection")), //
		STATE_26(new Doc().level(Level.FAULT).text("DCUndervoltageProtection")), //
		STATE_27(new Doc().level(Level.FAULT).text("DCInverseNoConnectionProtection")), //
		STATE_28(new Doc().level(Level.FAULT).text("DCDisconnectionProtection")), //
		STATE_29(new Doc().level(Level.FAULT).text("CommutingVoltageAbnormityProtection")), //
		STATE_30(new Doc().level(Level.FAULT).text("DCOvercurrentProtection")), //
		STATE_31(new Doc().level(Level.FAULT).text("Phase1PeakCurrentOverLimitProtection")), //
		STATE_32(new Doc().level(Level.FAULT).text("Phase2PeakCurrentOverLimitProtection")), //
		STATE_33(new Doc().level(Level.FAULT).text("Phase3PeakCurrentOverLimitProtection")), //
		STATE_34(new Doc().level(Level.FAULT).text("Phase1GridVoltageSamplingInvalidation")), //
		STATE_35(new Doc().level(Level.FAULT).text("Phase2VirtualCurrentOverLimitProtection")), //
		STATE_36(new Doc().level(Level.FAULT).text("Phase3VirtualCurrentOverLimitProtection")), //
		STATE_37(new Doc().level(Level.FAULT).text("Phase1GridVoltageSamplingInvalidation2")), //
		STATE_38(new Doc().level(Level.FAULT).text("Phase2ridVoltageSamplingInvalidation")), //
		STATE_39(new Doc().level(Level.FAULT).text("Phase3GridVoltageSamplingInvalidation")), //
		STATE_40(new Doc().level(Level.FAULT).text("Phase1InvertVoltageSamplingInvalidation")), //
		STATE_41(new Doc().level(Level.FAULT).text("Phase2InvertVoltageSamplingInvalidation")), //
		STATE_42(new Doc().level(Level.FAULT).text("Phase3InvertVoltageSamplingInvalidation")), //
		STATE_43(new Doc().level(Level.FAULT).text("ACCurrentSamplingInvalidation")), //
		STATE_44(new Doc().level(Level.FAULT).text("DCCurrentSamplingInvalidation")), //
		STATE_45(new Doc().level(Level.FAULT).text("Phase1OvertemperatureProtection")), //
		STATE_46(new Doc().level(Level.FAULT).text("Phase2OvertemperatureProtection")), //
		STATE_47(new Doc().level(Level.FAULT).text("Phase3OvertemperatureProtection")), //
		STATE_48(new Doc().level(Level.FAULT).text("Phase1TemperatureSamplingInvalidation")), //
		STATE_49(new Doc().level(Level.FAULT).text("Phase2TemperatureSamplingInvalidation")), //
		STATE_50(new Doc().level(Level.FAULT).text("Phase3TemperatureSamplingInvalidation")), //
		STATE_51(new Doc().level(Level.FAULT).text("Phase1PrechargeUnmetProtection")), //
		STATE_52(new Doc().level(Level.FAULT).text("Phase2PrechargeUnmetProtection")), //
		STATE_53(new Doc().level(Level.FAULT).text("Phase3PrechargeUnmetProtection")), //
		STATE_54(new Doc().level(Level.FAULT).text("UnadaptablePhaseSequenceErrorProtection")), //
		STATE_55(new Doc().level(Level.FAULT).text("DSPProtection")), //
		STATE_56(new Doc().level(Level.FAULT).text("Phase1GridVoltageSevereOvervoltageProtection")), //
		STATE_57(new Doc().level(Level.FAULT).text("Phase1GridVoltageGeneralOvervoltageProtection")), //
		STATE_58(new Doc().level(Level.FAULT).text("Phase2GridVoltageSevereOvervoltageProtection")), //
		STATE_59(new Doc().level(Level.FAULT).text("Phase2GridVoltageGeneralOvervoltageProtection")), //
		STATE_60(new Doc().level(Level.FAULT).text("Phase3GridVoltageSevereOvervoltageProtection")), //
		STATE_61(new Doc().level(Level.FAULT).text("Phase3GridVoltageGeneralOvervoltageProtection")), //
		STATE_62(new Doc().level(Level.FAULT).text("Phase1GridVoltageSevereUndervoltageProtection")), //
		STATE_63(new Doc().level(Level.FAULT).text("Phase1GridVoltageGeneralUndervoltageProtection")), //
		STATE_64(new Doc().level(Level.FAULT).text("Phase2GridVoltageSevereUndervoltageProtection")), //
		STATE_65(new Doc().level(Level.FAULT).text("Phase2GridVoltageGeneralUndervoltageProtection")), //
		STATE_66(new Doc().level(Level.FAULT).text("Phase3GridVoltageSevereUndervoltageProtection")), //
		STATE_67(new Doc().level(Level.FAULT).text("Phase3GridVoltageGeneralUndervoltageProtection")), //
		STATE_68(new Doc().level(Level.FAULT).text("SevereOverfrequncyProtection")), //
		STATE_69(new Doc().level(Level.FAULT).text("GeneralOverfrequncyProtection")), //
		STATE_70(new Doc().level(Level.FAULT).text("SevereUnderfrequncyProtection")), //
		STATE_71(new Doc().level(Level.FAULT).text("GeneralsUnderfrequncyProtection")), //
		STATE_72(new Doc().level(Level.FAULT).text("Phase1Gridloss")), //
		STATE_73(new Doc().level(Level.FAULT).text("Phase2Gridloss")), //
		STATE_74(new Doc().level(Level.FAULT).text("Phase3Gridloss")), //
		STATE_75(new Doc().level(Level.FAULT).text("IslandingProtection")), //
		STATE_76(new Doc().level(Level.FAULT).text("Phase1UnderVoltageRideThrough")), //
		STATE_77(new Doc().level(Level.FAULT).text("Phase2UnderVoltageRideThrough")), //
		STATE_78(new Doc().level(Level.FAULT).text("Phase3UnderVoltageRideThrough")), //
		STATE_79(new Doc().level(Level.FAULT).text("Phase1InverterVoltageSevereOvervoltageProtection")), //
		STATE_80(new Doc().level(Level.FAULT).text("Phase1InverterVoltageGeneralOvervoltageProtection")), //
		STATE_81(new Doc().level(Level.FAULT).text("Phase2InverterVoltageSevereOvervoltageProtection")), //
		STATE_82(new Doc().level(Level.FAULT).text("Phase2InverterVoltageGeneralOvervoltageProtection")), //
		STATE_83(new Doc().level(Level.FAULT).text("Phase3InverterVoltageSevereOvervoltageProtection")), //
		STATE_84(new Doc().level(Level.FAULT).text("Phase3InverterVoltageGeneralOvervoltageProtection")), //
		STATE_85(new Doc().level(Level.FAULT).text("InverterPeakVoltageHighProtectionCauseByACDisconnect")), //
		STATE_86(new Doc().level(Level.WARNING).text("DCPrechargeContactorInspectionAbnormity")), //
		STATE_87(new Doc().level(Level.WARNING).text("DCBreaker1InspectionAbnormity")), //
		STATE_88(new Doc().level(Level.WARNING).text("DCBreaker2InspectionAbnormity")), //
		STATE_89(new Doc().level(Level.WARNING).text("ACPrechargeContactorInspectionAbnormity")), //
		STATE_90(new Doc().level(Level.WARNING).text("ACMainontactorInspectionAbnormity")), //
		STATE_91(new Doc().level(Level.WARNING).text("ACBreakerInspectionAbnormity")), //
		STATE_92(new Doc().level(Level.WARNING).text("DCBreaker1CloseUnsuccessfully")), //
		STATE_93(new Doc().level(Level.WARNING).text("DCBreaker2CloseUnsuccessfully")), //
		STATE_94(new Doc().level(Level.WARNING).text("ControlSignalCloseAbnormallyInspectedBySystem")), //
		STATE_95(new Doc().level(Level.WARNING).text("ControlSignalOpenAbnormallyInspectedBySystem")), //
		STATE_96(new Doc().level(Level.WARNING).text("NeutralWireContactorCloseUnsuccessfully")), //
		STATE_97(new Doc().level(Level.WARNING).text("NeutralWireContactorOpenUnsuccessfully")), //
		STATE_98(new Doc().level(Level.WARNING).text("WorkDoorOpen")), //
		STATE_99(new Doc().level(Level.WARNING).text("Emergency1Stop")), //
		STATE_100(new Doc().level(Level.WARNING).text("ACBreakerCloseUnsuccessfully")), //
		STATE_101(new Doc().level(Level.WARNING).text("ControlSwitchStop")), //
		STATE_102(new Doc().level(Level.WARNING).text("GeneralOverload")), //
		STATE_103(new Doc().level(Level.WARNING).text("SevereOverload")), //
		STATE_104(new Doc().level(Level.WARNING).text("BatteryCurrentOverLimit")), //
		STATE_105(new Doc().level(Level.WARNING).text("PowerDecreaseCausedByOvertemperature")), //
		STATE_106(new Doc().level(Level.WARNING).text("InverterGeneralOvertemperature")), //
		STATE_107(new Doc().level(Level.WARNING).text("ACThreePhaseCurrentUnbalance")), //
		STATE_108(new Doc().level(Level.WARNING).text("RestoreFactorySettingUnsuccessfully")), //
		STATE_109(new Doc().level(Level.WARNING).text("PoleBoardInvalidation")), //
		STATE_110(new Doc().level(Level.WARNING).text("SelfInspectionFailed")), //
		STATE_111(new Doc().level(Level.WARNING).text("ReceiveBMSFaultAndStop")), //
		STATE_112(new Doc().level(Level.WARNING).text("RefrigerationEquipmentinvalidation")), //
		STATE_113(new Doc().level(Level.WARNING).text("LargeTemperatureDifferenceAmongIGBTThreePhases")), //
		STATE_114(new Doc().level(Level.WARNING).text("EEPROMParametersOverRange")), //
		STATE_115(new Doc().level(Level.WARNING).text("EEPROMParametersBackupFailed")), //
		STATE_116(new Doc().level(Level.WARNING).text("DCBreakerCloseunsuccessfully")), //
		STATE_117(new Doc().level(Level.WARNING).text("CommunicationBetweenInverterAndBSMUDisconnected")), //
		STATE_118(new Doc().level(Level.WARNING).text("CommunicationBetweenInverterAndMasterDisconnected")), //
		STATE_119(new Doc().level(Level.WARNING).text("CommunicationBetweenInverterAndUCDisconnected")), //
		STATE_120(new Doc().level(Level.WARNING).text("BMSStartOvertimeControlledByPCS")), //
		STATE_121(new Doc().level(Level.WARNING).text("BMSStopOvertimeControlledByPCS")), //
		STATE_122(new Doc().level(Level.WARNING).text("SyncSignalInvalidation")), //
		STATE_123(new Doc().level(Level.WARNING).text("SyncSignalContinuousCaputureFault")), //
		STATE_124(new Doc().level(Level.WARNING).text("SyncSignalSeveralTimesCaputureFault")), //
		STATE_125(new Doc().level(Level.WARNING).text("CurrentSamplingChannelAbnormityOnHighVoltageSide")), //
		STATE_126(new Doc().level(Level.WARNING).text("CurrentSamplingChannelAbnormityOnLowVoltageSide")), //
		STATE_127(new Doc().level(Level.WARNING).text("EEPROMParametersOverRange")), //
		STATE_128(new Doc().level(Level.WARNING).text("UpdateEEPROMFailed")), //
		STATE_129(new Doc().level(Level.WARNING).text("ReadEEPROMFailed")), //
		STATE_130(new Doc().level(Level.WARNING).text("CurrentSamplingChannelAbnormityBeforeInductance")), //
		STATE_131(new Doc().level(Level.WARNING).text("ReactorPowerDecreaseCausedByOvertemperature")), //
		STATE_132(new Doc().level(Level.WARNING).text("IGBTPowerDecreaseCausedByOvertemperature")), //
		STATE_133(new Doc().level(Level.WARNING).text("TemperatureChanel3PowerDecreaseCausedByOvertemperature")), //
		STATE_134(new Doc().level(Level.WARNING).text("TemperatureChanel4PowerDecreaseCausedByOvertemperature")), //
		STATE_135(new Doc().level(Level.WARNING).text("TemperatureChanel5PowerDecreaseCausedByOvertemperature")), //
		STATE_136(new Doc().level(Level.WARNING).text("TemperatureChanel6PowerDecreaseCausedByOvertemperature")), //
		STATE_137(new Doc().level(Level.WARNING).text("TemperatureChanel7PowerDecreaseCausedByOvertemperature")), //
		STATE_138(new Doc().level(Level.WARNING).text("TemperatureChanel8PowerDecreaseCausedByOvertemperature")), //
		STATE_139(new Doc().level(Level.WARNING).text("Fan1StopFailed")), //
		STATE_140(new Doc().level(Level.WARNING).text("Fan2StopFailed")), //
		STATE_141(new Doc().level(Level.WARNING).text("Fan3StopFailed")), //
		STATE_142(new Doc().level(Level.WARNING).text("Fan4StopFailed")), //
		STATE_143(new Doc().level(Level.WARNING).text("Fan1StartupFailed")), //
		STATE_144(new Doc().level(Level.WARNING).text("Fan2StartupFailed")), //
		STATE_145(new Doc().level(Level.WARNING).text("Fan3StartupFailed")), //
		STATE_146(new Doc().level(Level.WARNING).text("Fan4StartupFailed")), //
		STATE_147(new Doc().level(Level.WARNING).text("HighVoltageSideOvervoltage")), //
		STATE_148(new Doc().level(Level.WARNING).text("HighVoltageSideUndervoltage")), //
		STATE_149(new Doc().level(Level.WARNING).text("HighVoltageSideVoltageChangeUnconventionally")), //
		CELL_1_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_2_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_3_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_4_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_5_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_6_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_7_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_8_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_9_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_10_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_11_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_12_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_13_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_14_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_15_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_16_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_17_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_18_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_19_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_20_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_21_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_22_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_23_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_24_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_25_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_26_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_27_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_28_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_29_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_30_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_31_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_32_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_33_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_34_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_35_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_36_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_37_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_38_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_39_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_40_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_41_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_42_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_43_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_44_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_45_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_46_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_47_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_48_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_49_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_50_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_51_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_52_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_53_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_54_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_55_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_56_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_57_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_58_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_59_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_60_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_61_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_62_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_63_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_64_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_65_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_66_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_67_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_68_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_69_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_70_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_71_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_72_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_73_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_74_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_75_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_76_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_77_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_78_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_79_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_80_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_81_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_82_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_83_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_84_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_85_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_86_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_87_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_88_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_89_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_90_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_91_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_92_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_93_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_94_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_95_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_96_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_97_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_98_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_99_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_100_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_101_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_102_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_103_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_104_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_105_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_106_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_107_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_108_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_109_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_110_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_111_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_112_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_113_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_114_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_115_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_116_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_117_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_118_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_119_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_120_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_121_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_122_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_123_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_124_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_125_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_126_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_127_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_128_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_129_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_130_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_131_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_132_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_133_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_134_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_135_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_136_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_137_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_138_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_139_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_140_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_141_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_142_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_143_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_144_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_145_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_146_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_147_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_148_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_149_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_150_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_151_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_152_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_153_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_154_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_155_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_156_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_157_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_158_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_159_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_160_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_161_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_162_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_163_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_164_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_165_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_166_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_167_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_168_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_169_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_170_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_171_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_172_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_173_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_174_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_175_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_176_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_177_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_178_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_179_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_180_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_181_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_182_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_183_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_184_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_185_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_186_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_187_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_188_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_189_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_190_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_191_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_192_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_193_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_194_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_195_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_196_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_197_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_198_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_199_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_200_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_201_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_202_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_203_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_204_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_205_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_206_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_207_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_208_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_209_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_210_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_211_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_212_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_213_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_214_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_215_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_216_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_217_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_218_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_219_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_220_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_221_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_222_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_223_VOLTAGE((new Doc().unit(Unit.MILLIVOLT))), //
		CELL_224_VOLTAGE((new Doc().unit(Unit.MILLIVOLT)));//

		private final Doc doc;

		private ChannelId(Doc doc) {
			this.doc = doc;
		}

		@Override
		public Doc doc() {
			return this.doc;
		}

	}

	@Override
	protected ModbusProtocol defineModbusProtocol() {
		return new ModbusProtocol(this, //
				new FC3ReadRegistersTask(0x0101, Priority.LOW, //
						m(EssFeneconCommercial40.ChannelId.SYSTEM_STATE, new UnsignedWordElement(0x0101)),
						m(EssFeneconCommercial40.ChannelId.CONTROL_MODE, new UnsignedWordElement(0x0102)),
						new DummyRegisterElement(0x0103), // WorkMode: RemoteDispatch
						m(EssFeneconCommercial40.ChannelId.BATTERY_MAINTENANCE_STATE, new UnsignedWordElement(0x0104)),
						m(EssFeneconCommercial40.ChannelId.INVERTER_STATE, new UnsignedWordElement(0x0105)),
						m(SymmetricEss.ChannelId.GRID_MODE, new UnsignedWordElement(0x0106), //
								new ElementToChannelConverter((value) -> {
									Integer intValue = TypeUtils.<Integer>getAsType(OpenemsType.INTEGER, value);
									if (intValue != null) {
										switch (intValue) {
										case 1:
											return GridMode.OFF_GRID;
										case 2:
											return GridMode.ON_GRID;
										}
									}
									return GridMode.UNDEFINED;
								})),
						new DummyRegisterElement(0x0107), //
						m(EssFeneconCommercial40.ChannelId.PROTOCOL_VERSION, new UnsignedWordElement(0x0108)),
						m(EssFeneconCommercial40.ChannelId.SYSTEM_MANUFACTURER, new UnsignedWordElement(0x0109)),
						m(EssFeneconCommercial40.ChannelId.SYSTEM_TYPE, new UnsignedWordElement(0x010A)),
						new DummyRegisterElement(0x010B, 0x010F), //
						bm(new UnsignedWordElement(0x0110)) //
								.m(EssFeneconCommercial40.ChannelId.STATE_0, 2) //
								.m(EssFeneconCommercial40.ChannelId.STATE_1, 6) //
								.build(), //
						bm(new UnsignedWordElement(0x0111)) //
								.m(EssFeneconCommercial40.ChannelId.STATE_2, 3) //
								.m(EssFeneconCommercial40.ChannelId.STATE_3, 12) //
								.build(), //
						new DummyRegisterElement(0x0112, 0x0124), //
						bm(new UnsignedWordElement(0x0125)) //
								.m(EssFeneconCommercial40.ChannelId.STATE_4, 0) //
								.m(EssFeneconCommercial40.ChannelId.STATE_5, 1) //
								.m(EssFeneconCommercial40.ChannelId.STATE_6, 2) //
								.m(EssFeneconCommercial40.ChannelId.STATE_7, 4) //
								.m(EssFeneconCommercial40.ChannelId.STATE_8, 8) //
								.m(EssFeneconCommercial40.ChannelId.STATE_9, 9) //
								.build(), //
						bm(new UnsignedWordElement(0x0126)) //
								.m(EssFeneconCommercial40.ChannelId.STATE_10, 3) //
								.build(), //
						new DummyRegisterElement(0x0127, 0x014F), //
						m(EssFeneconCommercial40.ChannelId.BATTERY_STRING_SWITCH_STATE,
								new UnsignedWordElement(0x0150))), //
				new FC3ReadRegistersTask(0x0180, Priority.LOW, //
						bm(new UnsignedWordElement(0x0180)) //
								.m(EssFeneconCommercial40.ChannelId.STATE_11, 0) //
								.m(EssFeneconCommercial40.ChannelId.STATE_12, 1) //
								.m(EssFeneconCommercial40.ChannelId.STATE_13, 2) //
								.m(EssFeneconCommercial40.ChannelId.STATE_14, 3) //
								.m(EssFeneconCommercial40.ChannelId.STATE_15, 4) //
								.m(EssFeneconCommercial40.ChannelId.STATE_16, 5) //
								.m(EssFeneconCommercial40.ChannelId.STATE_17, 6) //
								.m(EssFeneconCommercial40.ChannelId.STATE_18, 7) //
								.m(EssFeneconCommercial40.ChannelId.STATE_19, 8) //
								.m(EssFeneconCommercial40.ChannelId.STATE_20, 9) //
								.m(EssFeneconCommercial40.ChannelId.STATE_21, 10) //
								.m(EssFeneconCommercial40.ChannelId.STATE_22, 11) //
								.m(EssFeneconCommercial40.ChannelId.STATE_23, 12) //
								.build(), //
						new DummyRegisterElement(0x0181), //
						bm(new UnsignedWordElement(0x0182)) //
								.m(EssFeneconCommercial40.ChannelId.STATE_24, 0) //
								.m(EssFeneconCommercial40.ChannelId.STATE_25, 1) //
								.m(EssFeneconCommercial40.ChannelId.STATE_26, 2) //
								.m(EssFeneconCommercial40.ChannelId.STATE_27, 3) //
								.m(EssFeneconCommercial40.ChannelId.STATE_28, 4) //
								.m(EssFeneconCommercial40.ChannelId.STATE_29, 5) //
								.m(EssFeneconCommercial40.ChannelId.STATE_30, 6) //
								.m(EssFeneconCommercial40.ChannelId.STATE_31, 7) //
								.m(EssFeneconCommercial40.ChannelId.STATE_32, 8) //
								.m(EssFeneconCommercial40.ChannelId.STATE_33, 9) //
								.m(EssFeneconCommercial40.ChannelId.STATE_34, 10) //
								.m(EssFeneconCommercial40.ChannelId.STATE_35, 11) //
								.m(EssFeneconCommercial40.ChannelId.STATE_36, 12) //
								.m(EssFeneconCommercial40.ChannelId.STATE_37, 13) //
								.m(EssFeneconCommercial40.ChannelId.STATE_38, 14) //
								.m(EssFeneconCommercial40.ChannelId.STATE_39, 15) //
								.build(), //
						bm(new UnsignedWordElement(0x0183)) //
								.m(EssFeneconCommercial40.ChannelId.STATE_40, 0) //
								.m(EssFeneconCommercial40.ChannelId.STATE_41, 1) //
								.m(EssFeneconCommercial40.ChannelId.STATE_42, 2) //
								.m(EssFeneconCommercial40.ChannelId.STATE_43, 3) //
								.m(EssFeneconCommercial40.ChannelId.STATE_44, 4) //
								.m(EssFeneconCommercial40.ChannelId.STATE_45, 5) //
								.m(EssFeneconCommercial40.ChannelId.STATE_46, 6) //
								.m(EssFeneconCommercial40.ChannelId.STATE_47, 7) //
								.m(EssFeneconCommercial40.ChannelId.STATE_48, 8) //
								.m(EssFeneconCommercial40.ChannelId.STATE_49, 9) //
								.m(EssFeneconCommercial40.ChannelId.STATE_50, 10) //
								.m(EssFeneconCommercial40.ChannelId.STATE_51, 11) //
								.m(EssFeneconCommercial40.ChannelId.STATE_52, 12) //
								.m(EssFeneconCommercial40.ChannelId.STATE_53, 13) //
								.m(EssFeneconCommercial40.ChannelId.STATE_54, 14) //
								.m(EssFeneconCommercial40.ChannelId.STATE_55, 15) //
								.build(), //
						bm(new UnsignedWordElement(0x0184)) //
								.m(EssFeneconCommercial40.ChannelId.STATE_56, 0) //
								.m(EssFeneconCommercial40.ChannelId.STATE_57, 1) //
								.m(EssFeneconCommercial40.ChannelId.STATE_58, 2) //
								.m(EssFeneconCommercial40.ChannelId.STATE_59, 3) //
								.m(EssFeneconCommercial40.ChannelId.STATE_60, 4) //
								.m(EssFeneconCommercial40.ChannelId.STATE_61, 5) //
								.m(EssFeneconCommercial40.ChannelId.STATE_62, 6) //
								.m(EssFeneconCommercial40.ChannelId.STATE_63, 7) //
								.m(EssFeneconCommercial40.ChannelId.STATE_64, 8) //
								.m(EssFeneconCommercial40.ChannelId.STATE_65, 9) //
								.m(EssFeneconCommercial40.ChannelId.STATE_66, 10) //
								.m(EssFeneconCommercial40.ChannelId.STATE_67, 11) //
								.m(EssFeneconCommercial40.ChannelId.STATE_68, 12) //
								.m(EssFeneconCommercial40.ChannelId.STATE_69, 13) //
								.m(EssFeneconCommercial40.ChannelId.STATE_70, 14) //
								.m(EssFeneconCommercial40.ChannelId.STATE_71, 15) //
								.build(), //
						bm(new UnsignedWordElement(0x0185)) //
								.m(EssFeneconCommercial40.ChannelId.STATE_72, 0) //
								.m(EssFeneconCommercial40.ChannelId.STATE_73, 1) //
								.m(EssFeneconCommercial40.ChannelId.STATE_74, 2) //
								.m(EssFeneconCommercial40.ChannelId.STATE_75, 3) //
								.m(EssFeneconCommercial40.ChannelId.STATE_76, 4) //
								.m(EssFeneconCommercial40.ChannelId.STATE_77, 5) //
								.m(EssFeneconCommercial40.ChannelId.STATE_78, 6) //
								.m(EssFeneconCommercial40.ChannelId.STATE_79, 7) //
								.m(EssFeneconCommercial40.ChannelId.STATE_80, 8) //
								.m(EssFeneconCommercial40.ChannelId.STATE_81, 9) //
								.m(EssFeneconCommercial40.ChannelId.STATE_82, 10) //
								.m(EssFeneconCommercial40.ChannelId.STATE_83, 11) //
								.m(EssFeneconCommercial40.ChannelId.STATE_84, 12) //
								.m(EssFeneconCommercial40.ChannelId.STATE_85, 13) //
								.build(), //
						bm(new UnsignedWordElement(0x0186)) //
								.m(EssFeneconCommercial40.ChannelId.STATE_86, 0) //
								.m(EssFeneconCommercial40.ChannelId.STATE_87, 1) //
								.m(EssFeneconCommercial40.ChannelId.STATE_88, 2) //
								.m(EssFeneconCommercial40.ChannelId.STATE_89, 3) //
								.m(EssFeneconCommercial40.ChannelId.STATE_90, 4) //
								.m(EssFeneconCommercial40.ChannelId.STATE_91, 5) //
								.m(EssFeneconCommercial40.ChannelId.STATE_92, 6) //
								.m(EssFeneconCommercial40.ChannelId.STATE_93, 7) //
								.m(EssFeneconCommercial40.ChannelId.STATE_94, 8) //
								.m(EssFeneconCommercial40.ChannelId.STATE_95, 9) //
								.m(EssFeneconCommercial40.ChannelId.STATE_96, 10) //
								.m(EssFeneconCommercial40.ChannelId.STATE_97, 11) //
								.m(EssFeneconCommercial40.ChannelId.STATE_98, 12) //
								.m(EssFeneconCommercial40.ChannelId.STATE_99, 13) //
								.m(EssFeneconCommercial40.ChannelId.STATE_100, 14) //
								.m(EssFeneconCommercial40.ChannelId.STATE_101, 15) //
								.build(), //
						bm(new UnsignedWordElement(0x0187)) //
								.m(EssFeneconCommercial40.ChannelId.STATE_102, 0) //
								.m(EssFeneconCommercial40.ChannelId.STATE_103, 1) //
								.m(EssFeneconCommercial40.ChannelId.STATE_104, 2) //
								.m(EssFeneconCommercial40.ChannelId.STATE_105, 3) //
								.m(EssFeneconCommercial40.ChannelId.STATE_106, 4) //
								.m(EssFeneconCommercial40.ChannelId.STATE_107, 5) //
								.m(EssFeneconCommercial40.ChannelId.STATE_108, 6) //
								.m(EssFeneconCommercial40.ChannelId.STATE_109, 7) //
								.m(EssFeneconCommercial40.ChannelId.STATE_110, 8) //
								.m(EssFeneconCommercial40.ChannelId.STATE_111, 9) //
								.m(EssFeneconCommercial40.ChannelId.STATE_112, 10) //
								.m(EssFeneconCommercial40.ChannelId.STATE_113, 11) //
								.m(EssFeneconCommercial40.ChannelId.STATE_114, 12) //
								.m(EssFeneconCommercial40.ChannelId.STATE_115, 13) //
								.m(EssFeneconCommercial40.ChannelId.STATE_116, 14) //
								.build(), //
						bm(new UnsignedWordElement(0x0188)) //
								.m(EssFeneconCommercial40.ChannelId.STATE_117, 0) //
								.m(EssFeneconCommercial40.ChannelId.STATE_118, 1) //
								.m(EssFeneconCommercial40.ChannelId.STATE_119, 2) //
								.m(EssFeneconCommercial40.ChannelId.STATE_120, 3) //
								.m(EssFeneconCommercial40.ChannelId.STATE_121, 4) //
								.m(EssFeneconCommercial40.ChannelId.STATE_122, 5) //
								.m(EssFeneconCommercial40.ChannelId.STATE_123, 6) //
								.m(EssFeneconCommercial40.ChannelId.STATE_124, 14) //
								.build() //
				), new FC3ReadRegistersTask(0x0200, Priority.HIGH, //
						m(EssFeneconCommercial40.ChannelId.BATTERY_VOLTAGE, new SignedWordElement(0x0200),
								ElementToChannelConverter.SCALE_FACTOR_2), //
						m(EssFeneconCommercial40.ChannelId.BATTERY_CURRENT, new SignedWordElement(0x0201),
								ElementToChannelConverter.SCALE_FACTOR_2), //
						m(EssFeneconCommercial40.ChannelId.BATTERY_POWER, new SignedWordElement(0x0202),
								ElementToChannelConverter.SCALE_FACTOR_2), //
						new DummyRegisterElement(0x0203, 0x0207),
						m(SymmetricEss.ChannelId.ACTIVE_CHARGE_ENERGY,
								new UnsignedDoublewordElement(0x0208).wordOrder(WordOrder.LSWMSW),
								ElementToChannelConverter.SCALE_FACTOR_2), //
						m(SymmetricEss.ChannelId.ACTIVE_DISCHARGE_ENERGY,
								new UnsignedDoublewordElement(0x020A).wordOrder(WordOrder.LSWMSW),
								ElementToChannelConverter.SCALE_FACTOR_2), //
						new DummyRegisterElement(0x020C, 0x020F), //
						m(EssFeneconCommercial40.ChannelId.GRID_ACTIVE_POWER, new SignedWordElement(0x0210),
								ElementToChannelConverter.SCALE_FACTOR_2), //
						m(SymmetricEss.ChannelId.REACTIVE_POWER, new SignedWordElement(0x0211),
								ElementToChannelConverter.SCALE_FACTOR_2), //
						m(EssFeneconCommercial40.ChannelId.APPARENT_POWER, new UnsignedWordElement(0x0212),
								ElementToChannelConverter.SCALE_FACTOR_2), //
						m(EssFeneconCommercial40.ChannelId.CURRENT_L1, new SignedWordElement(0x0213),
								ElementToChannelConverter.SCALE_FACTOR_2), //
						m(EssFeneconCommercial40.ChannelId.CURRENT_L2, new SignedWordElement(0x0214),
								ElementToChannelConverter.SCALE_FACTOR_2), //
						m(EssFeneconCommercial40.ChannelId.CURRENT_L3, new SignedWordElement(0x0215),
								ElementToChannelConverter.SCALE_FACTOR_2), //
						new DummyRegisterElement(0x0216, 0x218), //
						m(EssFeneconCommercial40.ChannelId.VOLTAGE_L1, new UnsignedWordElement(0x0219),
								ElementToChannelConverter.SCALE_FACTOR_2), //
						m(EssFeneconCommercial40.ChannelId.VOLTAGE_L2, new UnsignedWordElement(0x021A),
								ElementToChannelConverter.SCALE_FACTOR_2), //
						m(EssFeneconCommercial40.ChannelId.VOLTAGE_L3, new UnsignedWordElement(0x021B),
								ElementToChannelConverter.SCALE_FACTOR_2), //
						m(EssFeneconCommercial40.ChannelId.FREQUENCY, new UnsignedWordElement(0x021C))), //
				new FC3ReadRegistersTask(0x0222, Priority.HIGH, //
						m(EssFeneconCommercial40.ChannelId.INVERTER_VOLTAGE_L1, new UnsignedWordElement(0x0222),
								ElementToChannelConverter.SCALE_FACTOR_2), //
						m(EssFeneconCommercial40.ChannelId.INVERTER_VOLTAGE_L2, new UnsignedWordElement(0x0223),
								ElementToChannelConverter.SCALE_FACTOR_2), //
						m(EssFeneconCommercial40.ChannelId.INVERTER_VOLTAGE_L3, new UnsignedWordElement(0x0224),
								ElementToChannelConverter.SCALE_FACTOR_2), //
						m(EssFeneconCommercial40.ChannelId.INVERTER_CURRENT_L1, new SignedWordElement(0x0225),
								ElementToChannelConverter.SCALE_FACTOR_2), //
						m(EssFeneconCommercial40.ChannelId.INVERTER_CURRENT_L2, new SignedWordElement(0x0226),
								ElementToChannelConverter.SCALE_FACTOR_2), //
						m(EssFeneconCommercial40.ChannelId.INVERTER_CURRENT_L3, new SignedWordElement(0x0227),
								ElementToChannelConverter.SCALE_FACTOR_2), //
						m(SymmetricEss.ChannelId.ACTIVE_POWER, new SignedWordElement(0x0228),
								ElementToChannelConverter.SCALE_FACTOR_2), //
						new DummyRegisterElement(0x0229, 0x022F), //
						m(ChannelId.ORIGINAL_ALLOWED_CHARGE_POWER, new SignedWordElement(0x0230),
								ElementToChannelConverter.SCALE_FACTOR_2), //
						m(ChannelId.ORIGINAL_ALLOWED_DISCHARGE_POWER, new UnsignedWordElement(0x0231),
								ElementToChannelConverter.SCALE_FACTOR_2), //
						m(SymmetricEss.ChannelId.MAX_APPARENT_POWER, new UnsignedWordElement(0x0232),
								ElementToChannelConverter.SCALE_FACTOR_2), //
						new DummyRegisterElement(0x0233, 0x23F),
						m(EssFeneconCommercial40.ChannelId.IPM_TEMPERATURE_L1, new SignedWordElement(0x0240)), //
						m(EssFeneconCommercial40.ChannelId.IPM_TEMPERATURE_L2, new SignedWordElement(0x0241)), //
						m(EssFeneconCommercial40.ChannelId.IPM_TEMPERATURE_L3, new SignedWordElement(0x0242)), //
						new DummyRegisterElement(0x0243, 0x0248), //
						m(EssFeneconCommercial40.ChannelId.TRANSFORMER_TEMPERATURE_L2, new SignedWordElement(0x0249))), //
				new FC16WriteRegistersTask(0x0500, //
						m(EssFeneconCommercial40.ChannelId.SET_WORK_STATE, new UnsignedWordElement(0x0500))), //
				new FC16WriteRegistersTask(0x0501, //
						m(EssFeneconCommercial40.ChannelId.SET_ACTIVE_POWER, new SignedWordElement(0x0501),
								ElementToChannelConverter.SCALE_FACTOR_2), //
						m(EssFeneconCommercial40.ChannelId.SET_REACTIVE_POWER, new SignedWordElement(0x0502),
								ElementToChannelConverter.SCALE_FACTOR_2), //
						m(EssFeneconCommercial40.ChannelId.SET_PV_POWER_LIMIT, new UnsignedWordElement(0x0503),
								ElementToChannelConverter.SCALE_FACTOR_2)), //
				new FC3ReadRegistersTask(0xA000, Priority.LOW, //
						m(EssFeneconCommercial40.ChannelId.BMS_DCDC_WORK_STATE, new UnsignedWordElement(0xA000)), //
						m(EssFeneconCommercial40.ChannelId.BMS_DCDC_WORK_MODE, new UnsignedWordElement(0xA001))), //
				new FC3ReadRegistersTask(0xA100, Priority.LOW, //
						bm(new UnsignedWordElement(0xA100)) //
								.m(EssFeneconCommercial40.ChannelId.STATE_125, 0) //
								.m(EssFeneconCommercial40.ChannelId.STATE_126, 1) //
								.m(EssFeneconCommercial40.ChannelId.STATE_127, 6) //
								.m(EssFeneconCommercial40.ChannelId.STATE_128, 7) //
								.m(EssFeneconCommercial40.ChannelId.STATE_129, 8) //
								.m(EssFeneconCommercial40.ChannelId.STATE_130, 9) //
								.build(), //
						bm(new UnsignedWordElement(0xA101)) //
								.m(EssFeneconCommercial40.ChannelId.STATE_131, 0) //
								.m(EssFeneconCommercial40.ChannelId.STATE_132, 1) //
								.m(EssFeneconCommercial40.ChannelId.STATE_133, 2) //
								.m(EssFeneconCommercial40.ChannelId.STATE_134, 3) //
								.m(EssFeneconCommercial40.ChannelId.STATE_135, 4) //
								.m(EssFeneconCommercial40.ChannelId.STATE_136, 5) //
								.m(EssFeneconCommercial40.ChannelId.STATE_137, 6) //
								.m(EssFeneconCommercial40.ChannelId.STATE_138, 7) //
								.m(EssFeneconCommercial40.ChannelId.STATE_139, 8) //
								.m(EssFeneconCommercial40.ChannelId.STATE_140, 9) //
								.m(EssFeneconCommercial40.ChannelId.STATE_141, 10) //
								.m(EssFeneconCommercial40.ChannelId.STATE_142, 11) //
								.m(EssFeneconCommercial40.ChannelId.STATE_143, 12) //
								.m(EssFeneconCommercial40.ChannelId.STATE_144, 13) //
								.m(EssFeneconCommercial40.ChannelId.STATE_145, 14) //
								.m(EssFeneconCommercial40.ChannelId.STATE_146, 15) //
								.build(), //
						bm(new UnsignedWordElement(0xA102)) //
								.m(EssFeneconCommercial40.ChannelId.STATE_147, 0) //
								.m(EssFeneconCommercial40.ChannelId.STATE_148, 1) //
								.m(EssFeneconCommercial40.ChannelId.STATE_149, 2) //
								.build()), //
				new FC3ReadRegistersTask(0x1500, Priority.LOW,
						m(EssFeneconCommercial40.ChannelId.CELL_1_VOLTAGE, new UnsignedWordElement(0x1500)),
						m(EssFeneconCommercial40.ChannelId.CELL_2_VOLTAGE, new UnsignedWordElement(0x1501)),
						m(EssFeneconCommercial40.ChannelId.CELL_3_VOLTAGE, new UnsignedWordElement(0x1502)),
						m(EssFeneconCommercial40.ChannelId.CELL_4_VOLTAGE, new UnsignedWordElement(0x1503)),
						m(EssFeneconCommercial40.ChannelId.CELL_5_VOLTAGE, new UnsignedWordElement(0x1504)),
						m(EssFeneconCommercial40.ChannelId.CELL_6_VOLTAGE, new UnsignedWordElement(0x1505)),
						m(EssFeneconCommercial40.ChannelId.CELL_7_VOLTAGE, new UnsignedWordElement(0x1506)),
						m(EssFeneconCommercial40.ChannelId.CELL_8_VOLTAGE, new UnsignedWordElement(0x1507)),
						m(EssFeneconCommercial40.ChannelId.CELL_9_VOLTAGE, new UnsignedWordElement(0x1508)),
						m(EssFeneconCommercial40.ChannelId.CELL_10_VOLTAGE, new UnsignedWordElement(0x1509)),
						m(EssFeneconCommercial40.ChannelId.CELL_11_VOLTAGE, new UnsignedWordElement(0x150A)),
						m(EssFeneconCommercial40.ChannelId.CELL_12_VOLTAGE, new UnsignedWordElement(0x150B)),
						m(EssFeneconCommercial40.ChannelId.CELL_13_VOLTAGE, new UnsignedWordElement(0x150C)),
						m(EssFeneconCommercial40.ChannelId.CELL_14_VOLTAGE, new UnsignedWordElement(0x150D)),
						m(EssFeneconCommercial40.ChannelId.CELL_15_VOLTAGE, new UnsignedWordElement(0x150E)),
						m(EssFeneconCommercial40.ChannelId.CELL_16_VOLTAGE, new UnsignedWordElement(0x150F)),
						m(EssFeneconCommercial40.ChannelId.CELL_17_VOLTAGE, new UnsignedWordElement(0x1510)),
						m(EssFeneconCommercial40.ChannelId.CELL_18_VOLTAGE, new UnsignedWordElement(0x1511)),
						m(EssFeneconCommercial40.ChannelId.CELL_19_VOLTAGE, new UnsignedWordElement(0x1512)),
						m(EssFeneconCommercial40.ChannelId.CELL_20_VOLTAGE, new UnsignedWordElement(0x1513)),
						m(EssFeneconCommercial40.ChannelId.CELL_21_VOLTAGE, new UnsignedWordElement(0x1514)),
						m(EssFeneconCommercial40.ChannelId.CELL_22_VOLTAGE, new UnsignedWordElement(0x1515)),
						m(EssFeneconCommercial40.ChannelId.CELL_23_VOLTAGE, new UnsignedWordElement(0x1516)),
						m(EssFeneconCommercial40.ChannelId.CELL_24_VOLTAGE, new UnsignedWordElement(0x1517)),
						m(EssFeneconCommercial40.ChannelId.CELL_25_VOLTAGE, new UnsignedWordElement(0x1518)),
						m(EssFeneconCommercial40.ChannelId.CELL_26_VOLTAGE, new UnsignedWordElement(0x1519)),
						m(EssFeneconCommercial40.ChannelId.CELL_27_VOLTAGE, new UnsignedWordElement(0x151A)),
						m(EssFeneconCommercial40.ChannelId.CELL_28_VOLTAGE, new UnsignedWordElement(0x151B)),
						m(EssFeneconCommercial40.ChannelId.CELL_29_VOLTAGE, new UnsignedWordElement(0x151C)),
						m(EssFeneconCommercial40.ChannelId.CELL_30_VOLTAGE, new UnsignedWordElement(0x151D)),
						m(EssFeneconCommercial40.ChannelId.CELL_31_VOLTAGE, new UnsignedWordElement(0x151E)),
						m(EssFeneconCommercial40.ChannelId.CELL_32_VOLTAGE, new UnsignedWordElement(0x151F)),
						m(EssFeneconCommercial40.ChannelId.CELL_33_VOLTAGE, new UnsignedWordElement(0x1520)),
						m(EssFeneconCommercial40.ChannelId.CELL_34_VOLTAGE, new UnsignedWordElement(0x1521)),
						m(EssFeneconCommercial40.ChannelId.CELL_35_VOLTAGE, new UnsignedWordElement(0x1522)),
						m(EssFeneconCommercial40.ChannelId.CELL_36_VOLTAGE, new UnsignedWordElement(0x1523)),
						m(EssFeneconCommercial40.ChannelId.CELL_37_VOLTAGE, new UnsignedWordElement(0x1524)),
						m(EssFeneconCommercial40.ChannelId.CELL_38_VOLTAGE, new UnsignedWordElement(0x1525)),
						m(EssFeneconCommercial40.ChannelId.CELL_39_VOLTAGE, new UnsignedWordElement(0x1526)),
						m(EssFeneconCommercial40.ChannelId.CELL_40_VOLTAGE, new UnsignedWordElement(0x1527)),
						m(EssFeneconCommercial40.ChannelId.CELL_41_VOLTAGE, new UnsignedWordElement(0x1528)),
						m(EssFeneconCommercial40.ChannelId.CELL_42_VOLTAGE, new UnsignedWordElement(0x1529)),
						m(EssFeneconCommercial40.ChannelId.CELL_43_VOLTAGE, new UnsignedWordElement(0x152A)),
						m(EssFeneconCommercial40.ChannelId.CELL_44_VOLTAGE, new UnsignedWordElement(0x152B)),
						m(EssFeneconCommercial40.ChannelId.CELL_45_VOLTAGE, new UnsignedWordElement(0x152C)),
						m(EssFeneconCommercial40.ChannelId.CELL_46_VOLTAGE, new UnsignedWordElement(0x152D)),
						m(EssFeneconCommercial40.ChannelId.CELL_47_VOLTAGE, new UnsignedWordElement(0x152E)),
						m(EssFeneconCommercial40.ChannelId.CELL_48_VOLTAGE, new UnsignedWordElement(0x152F)),
						m(EssFeneconCommercial40.ChannelId.CELL_49_VOLTAGE, new UnsignedWordElement(0x1530)),
						m(EssFeneconCommercial40.ChannelId.CELL_50_VOLTAGE, new UnsignedWordElement(0x1531)),
						m(EssFeneconCommercial40.ChannelId.CELL_51_VOLTAGE, new UnsignedWordElement(0x1532)),
						m(EssFeneconCommercial40.ChannelId.CELL_52_VOLTAGE, new UnsignedWordElement(0x1533)),
						m(EssFeneconCommercial40.ChannelId.CELL_53_VOLTAGE, new UnsignedWordElement(0x1534)),
						m(EssFeneconCommercial40.ChannelId.CELL_54_VOLTAGE, new UnsignedWordElement(0x1535)),
						m(EssFeneconCommercial40.ChannelId.CELL_55_VOLTAGE, new UnsignedWordElement(0x1536)),
						m(EssFeneconCommercial40.ChannelId.CELL_56_VOLTAGE, new UnsignedWordElement(0x1537)),
						m(EssFeneconCommercial40.ChannelId.CELL_57_VOLTAGE, new UnsignedWordElement(0x1538)),
						m(EssFeneconCommercial40.ChannelId.CELL_58_VOLTAGE, new UnsignedWordElement(0x1539)),
						m(EssFeneconCommercial40.ChannelId.CELL_59_VOLTAGE, new UnsignedWordElement(0x153A)),
						m(EssFeneconCommercial40.ChannelId.CELL_60_VOLTAGE, new UnsignedWordElement(0x153B)),
						m(EssFeneconCommercial40.ChannelId.CELL_61_VOLTAGE, new UnsignedWordElement(0x153C)),
						m(EssFeneconCommercial40.ChannelId.CELL_62_VOLTAGE, new UnsignedWordElement(0x153D)),
						m(EssFeneconCommercial40.ChannelId.CELL_63_VOLTAGE, new UnsignedWordElement(0x153E)),
						m(EssFeneconCommercial40.ChannelId.CELL_64_VOLTAGE, new UnsignedWordElement(0x153F)),
						m(EssFeneconCommercial40.ChannelId.CELL_65_VOLTAGE, new UnsignedWordElement(0x1540)),
						m(EssFeneconCommercial40.ChannelId.CELL_66_VOLTAGE, new UnsignedWordElement(0x1541)),
						m(EssFeneconCommercial40.ChannelId.CELL_67_VOLTAGE, new UnsignedWordElement(0x1542)),
						m(EssFeneconCommercial40.ChannelId.CELL_68_VOLTAGE, new UnsignedWordElement(0x1543)),
						m(EssFeneconCommercial40.ChannelId.CELL_69_VOLTAGE, new UnsignedWordElement(0x1544)),
						m(EssFeneconCommercial40.ChannelId.CELL_70_VOLTAGE, new UnsignedWordElement(0x1545)),
						m(EssFeneconCommercial40.ChannelId.CELL_71_VOLTAGE, new UnsignedWordElement(0x1546)),
						m(EssFeneconCommercial40.ChannelId.CELL_72_VOLTAGE, new UnsignedWordElement(0x1547)),
						m(EssFeneconCommercial40.ChannelId.CELL_73_VOLTAGE, new UnsignedWordElement(0x1548)),
						m(EssFeneconCommercial40.ChannelId.CELL_74_VOLTAGE, new UnsignedWordElement(0x1549)),
						m(EssFeneconCommercial40.ChannelId.CELL_75_VOLTAGE, new UnsignedWordElement(0x154A)),
						m(EssFeneconCommercial40.ChannelId.CELL_76_VOLTAGE, new UnsignedWordElement(0x154B)),
						m(EssFeneconCommercial40.ChannelId.CELL_77_VOLTAGE, new UnsignedWordElement(0x154C)),
						m(EssFeneconCommercial40.ChannelId.CELL_78_VOLTAGE, new UnsignedWordElement(0x154D)),
						m(EssFeneconCommercial40.ChannelId.CELL_79_VOLTAGE, new UnsignedWordElement(0x154E)),
						m(EssFeneconCommercial40.ChannelId.CELL_80_VOLTAGE, new UnsignedWordElement(0x154F))),
				new FC3ReadRegistersTask(0x1500, Priority.LOW,
						m(EssFeneconCommercial40.ChannelId.CELL_81_VOLTAGE, new UnsignedWordElement(0x1550)),
						m(EssFeneconCommercial40.ChannelId.CELL_82_VOLTAGE, new UnsignedWordElement(0x1551)),
						m(EssFeneconCommercial40.ChannelId.CELL_83_VOLTAGE, new UnsignedWordElement(0x1552)),
						m(EssFeneconCommercial40.ChannelId.CELL_84_VOLTAGE, new UnsignedWordElement(0x1553)),
						m(EssFeneconCommercial40.ChannelId.CELL_85_VOLTAGE, new UnsignedWordElement(0x1554)),
						m(EssFeneconCommercial40.ChannelId.CELL_86_VOLTAGE, new UnsignedWordElement(0x1555)),
						m(EssFeneconCommercial40.ChannelId.CELL_87_VOLTAGE, new UnsignedWordElement(0x1556)),
						m(EssFeneconCommercial40.ChannelId.CELL_88_VOLTAGE, new UnsignedWordElement(0x1557)),
						m(EssFeneconCommercial40.ChannelId.CELL_89_VOLTAGE, new UnsignedWordElement(0x1558)),
						m(EssFeneconCommercial40.ChannelId.CELL_90_VOLTAGE, new UnsignedWordElement(0x1559)),
						m(EssFeneconCommercial40.ChannelId.CELL_91_VOLTAGE, new UnsignedWordElement(0x155A)),
						m(EssFeneconCommercial40.ChannelId.CELL_92_VOLTAGE, new UnsignedWordElement(0x155B)),
						m(EssFeneconCommercial40.ChannelId.CELL_93_VOLTAGE, new UnsignedWordElement(0x155C)),
						m(EssFeneconCommercial40.ChannelId.CELL_94_VOLTAGE, new UnsignedWordElement(0x155D)),
						m(EssFeneconCommercial40.ChannelId.CELL_95_VOLTAGE, new UnsignedWordElement(0x155E)),
						m(EssFeneconCommercial40.ChannelId.CELL_96_VOLTAGE, new UnsignedWordElement(0x155F)),
						m(EssFeneconCommercial40.ChannelId.CELL_97_VOLTAGE, new UnsignedWordElement(0x1560)),
						m(EssFeneconCommercial40.ChannelId.CELL_98_VOLTAGE, new UnsignedWordElement(0x1561)),
						m(EssFeneconCommercial40.ChannelId.CELL_99_VOLTAGE, new UnsignedWordElement(0x1562)),
						m(EssFeneconCommercial40.ChannelId.CELL_100_VOLTAGE, new UnsignedWordElement(0x1563)),
						m(EssFeneconCommercial40.ChannelId.CELL_101_VOLTAGE, new UnsignedWordElement(0x1564)),
						m(EssFeneconCommercial40.ChannelId.CELL_102_VOLTAGE, new UnsignedWordElement(0x1565)),
						m(EssFeneconCommercial40.ChannelId.CELL_103_VOLTAGE, new UnsignedWordElement(0x1566)),
						m(EssFeneconCommercial40.ChannelId.CELL_104_VOLTAGE, new UnsignedWordElement(0x1567)),
						m(EssFeneconCommercial40.ChannelId.CELL_105_VOLTAGE, new UnsignedWordElement(0x1568)),
						m(EssFeneconCommercial40.ChannelId.CELL_106_VOLTAGE, new UnsignedWordElement(0x1569)),
						m(EssFeneconCommercial40.ChannelId.CELL_107_VOLTAGE, new UnsignedWordElement(0x156A)),
						m(EssFeneconCommercial40.ChannelId.CELL_108_VOLTAGE, new UnsignedWordElement(0x156B)),
						m(EssFeneconCommercial40.ChannelId.CELL_109_VOLTAGE, new UnsignedWordElement(0x156C)),
						m(EssFeneconCommercial40.ChannelId.CELL_110_VOLTAGE, new UnsignedWordElement(0x156D)),
						m(EssFeneconCommercial40.ChannelId.CELL_111_VOLTAGE, new UnsignedWordElement(0x156E)),
						m(EssFeneconCommercial40.ChannelId.CELL_112_VOLTAGE, new UnsignedWordElement(0x156F)),
						m(EssFeneconCommercial40.ChannelId.CELL_113_VOLTAGE, new UnsignedWordElement(0x1570)),
						m(EssFeneconCommercial40.ChannelId.CELL_114_VOLTAGE, new UnsignedWordElement(0x1571)),
						m(EssFeneconCommercial40.ChannelId.CELL_115_VOLTAGE, new UnsignedWordElement(0x1572)),
						m(EssFeneconCommercial40.ChannelId.CELL_116_VOLTAGE, new UnsignedWordElement(0x1573)),
						m(EssFeneconCommercial40.ChannelId.CELL_117_VOLTAGE, new UnsignedWordElement(0x1574)),
						m(EssFeneconCommercial40.ChannelId.CELL_118_VOLTAGE, new UnsignedWordElement(0x1575)),
						m(EssFeneconCommercial40.ChannelId.CELL_119_VOLTAGE, new UnsignedWordElement(0x1576)),
						m(EssFeneconCommercial40.ChannelId.CELL_120_VOLTAGE, new UnsignedWordElement(0x1577)),
						m(EssFeneconCommercial40.ChannelId.CELL_121_VOLTAGE, new UnsignedWordElement(0x1578)),
						m(EssFeneconCommercial40.ChannelId.CELL_122_VOLTAGE, new UnsignedWordElement(0x1579)),
						m(EssFeneconCommercial40.ChannelId.CELL_123_VOLTAGE, new UnsignedWordElement(0x157A)),
						m(EssFeneconCommercial40.ChannelId.CELL_124_VOLTAGE, new UnsignedWordElement(0x157B)),
						m(EssFeneconCommercial40.ChannelId.CELL_125_VOLTAGE, new UnsignedWordElement(0x157C)),
						m(EssFeneconCommercial40.ChannelId.CELL_126_VOLTAGE, new UnsignedWordElement(0x157D)),
						m(EssFeneconCommercial40.ChannelId.CELL_127_VOLTAGE, new UnsignedWordElement(0x157E)),
						m(EssFeneconCommercial40.ChannelId.CELL_128_VOLTAGE, new UnsignedWordElement(0x157F)),
						m(EssFeneconCommercial40.ChannelId.CELL_129_VOLTAGE, new UnsignedWordElement(0x1580)),
						m(EssFeneconCommercial40.ChannelId.CELL_130_VOLTAGE, new UnsignedWordElement(0x1581)),
						m(EssFeneconCommercial40.ChannelId.CELL_131_VOLTAGE, new UnsignedWordElement(0x1582)),
						m(EssFeneconCommercial40.ChannelId.CELL_132_VOLTAGE, new UnsignedWordElement(0x1583)),
						m(EssFeneconCommercial40.ChannelId.CELL_133_VOLTAGE, new UnsignedWordElement(0x1584)),
						m(EssFeneconCommercial40.ChannelId.CELL_134_VOLTAGE, new UnsignedWordElement(0x1585)),
						m(EssFeneconCommercial40.ChannelId.CELL_135_VOLTAGE, new UnsignedWordElement(0x1586)),
						m(EssFeneconCommercial40.ChannelId.CELL_136_VOLTAGE, new UnsignedWordElement(0x1587)),
						m(EssFeneconCommercial40.ChannelId.CELL_137_VOLTAGE, new UnsignedWordElement(0x1588)),
						m(EssFeneconCommercial40.ChannelId.CELL_138_VOLTAGE, new UnsignedWordElement(0x1589)),
						m(EssFeneconCommercial40.ChannelId.CELL_139_VOLTAGE, new UnsignedWordElement(0x158A)),
						m(EssFeneconCommercial40.ChannelId.CELL_140_VOLTAGE, new UnsignedWordElement(0x158B)),
						m(EssFeneconCommercial40.ChannelId.CELL_141_VOLTAGE, new UnsignedWordElement(0x158C)),
						m(EssFeneconCommercial40.ChannelId.CELL_142_VOLTAGE, new UnsignedWordElement(0x158D)),
						m(EssFeneconCommercial40.ChannelId.CELL_143_VOLTAGE, new UnsignedWordElement(0x158E)),
						m(EssFeneconCommercial40.ChannelId.CELL_144_VOLTAGE, new UnsignedWordElement(0x158F),
								ElementToChannelConverter.SCALE_FACTOR_1),
						m(EssFeneconCommercial40.ChannelId.CELL_145_VOLTAGE, new UnsignedWordElement(0x1590)),
						m(EssFeneconCommercial40.ChannelId.CELL_146_VOLTAGE, new UnsignedWordElement(0x1591)),
						m(EssFeneconCommercial40.ChannelId.CELL_147_VOLTAGE, new UnsignedWordElement(0x1592)),
						m(EssFeneconCommercial40.ChannelId.CELL_148_VOLTAGE, new UnsignedWordElement(0x1593)),
						m(EssFeneconCommercial40.ChannelId.CELL_149_VOLTAGE, new UnsignedWordElement(0x1594)),
						m(EssFeneconCommercial40.ChannelId.CELL_150_VOLTAGE, new UnsignedWordElement(0x1595)),
						m(EssFeneconCommercial40.ChannelId.CELL_151_VOLTAGE, new UnsignedWordElement(0x1596)),
						m(EssFeneconCommercial40.ChannelId.CELL_152_VOLTAGE, new UnsignedWordElement(0x1597)),
						m(EssFeneconCommercial40.ChannelId.CELL_153_VOLTAGE, new UnsignedWordElement(0x1598)),
						m(EssFeneconCommercial40.ChannelId.CELL_154_VOLTAGE, new UnsignedWordElement(0x1599)),
						m(EssFeneconCommercial40.ChannelId.CELL_155_VOLTAGE, new UnsignedWordElement(0x159A)),
						m(EssFeneconCommercial40.ChannelId.CELL_156_VOLTAGE, new UnsignedWordElement(0x159B)),
						m(EssFeneconCommercial40.ChannelId.CELL_157_VOLTAGE, new UnsignedWordElement(0x159C)),
						m(EssFeneconCommercial40.ChannelId.CELL_158_VOLTAGE, new UnsignedWordElement(0x159D)),
						m(EssFeneconCommercial40.ChannelId.CELL_159_VOLTAGE, new UnsignedWordElement(0x159E)),
						m(EssFeneconCommercial40.ChannelId.CELL_160_VOLTAGE, new UnsignedWordElement(0x159F))),
				new FC3ReadRegistersTask(0x1500, Priority.LOW,
						m(EssFeneconCommercial40.ChannelId.CELL_161_VOLTAGE, new UnsignedWordElement(0x15A0)),
						m(EssFeneconCommercial40.ChannelId.CELL_162_VOLTAGE, new UnsignedWordElement(0x15A1)),
						m(EssFeneconCommercial40.ChannelId.CELL_163_VOLTAGE, new UnsignedWordElement(0x15A2)),
						m(EssFeneconCommercial40.ChannelId.CELL_164_VOLTAGE, new UnsignedWordElement(0x15A3)),
						m(EssFeneconCommercial40.ChannelId.CELL_165_VOLTAGE, new UnsignedWordElement(0x15A4)),
						m(EssFeneconCommercial40.ChannelId.CELL_166_VOLTAGE, new UnsignedWordElement(0x15A5)),
						m(EssFeneconCommercial40.ChannelId.CELL_167_VOLTAGE, new UnsignedWordElement(0x15A6)),
						m(EssFeneconCommercial40.ChannelId.CELL_168_VOLTAGE, new UnsignedWordElement(0x15A7)),
						m(EssFeneconCommercial40.ChannelId.CELL_169_VOLTAGE, new UnsignedWordElement(0x15A8)),
						m(EssFeneconCommercial40.ChannelId.CELL_170_VOLTAGE, new UnsignedWordElement(0x15A9)),
						m(EssFeneconCommercial40.ChannelId.CELL_171_VOLTAGE, new UnsignedWordElement(0x15AA)),
						m(EssFeneconCommercial40.ChannelId.CELL_172_VOLTAGE, new UnsignedWordElement(0x15AB)),
						m(EssFeneconCommercial40.ChannelId.CELL_173_VOLTAGE, new UnsignedWordElement(0x15AC)),
						m(EssFeneconCommercial40.ChannelId.CELL_174_VOLTAGE, new UnsignedWordElement(0x15AD)),
						m(EssFeneconCommercial40.ChannelId.CELL_175_VOLTAGE, new UnsignedWordElement(0x15AE)),
						m(EssFeneconCommercial40.ChannelId.CELL_176_VOLTAGE, new UnsignedWordElement(0x15AF)),
						m(EssFeneconCommercial40.ChannelId.CELL_177_VOLTAGE, new UnsignedWordElement(0x15B0)),
						m(EssFeneconCommercial40.ChannelId.CELL_178_VOLTAGE, new UnsignedWordElement(0x15B1)),
						m(EssFeneconCommercial40.ChannelId.CELL_179_VOLTAGE, new UnsignedWordElement(0x15B2)),
						m(EssFeneconCommercial40.ChannelId.CELL_180_VOLTAGE, new UnsignedWordElement(0x15B3)),
						m(EssFeneconCommercial40.ChannelId.CELL_181_VOLTAGE, new UnsignedWordElement(0x15B4)),
						m(EssFeneconCommercial40.ChannelId.CELL_182_VOLTAGE, new UnsignedWordElement(0x15B5)),
						m(EssFeneconCommercial40.ChannelId.CELL_183_VOLTAGE, new UnsignedWordElement(0x15B6)),
						m(EssFeneconCommercial40.ChannelId.CELL_184_VOLTAGE, new UnsignedWordElement(0x15B7)),
						m(EssFeneconCommercial40.ChannelId.CELL_185_VOLTAGE, new UnsignedWordElement(0x15B8)),
						m(EssFeneconCommercial40.ChannelId.CELL_186_VOLTAGE, new UnsignedWordElement(0x15B9)),
						m(EssFeneconCommercial40.ChannelId.CELL_187_VOLTAGE, new UnsignedWordElement(0x15BA)),
						m(EssFeneconCommercial40.ChannelId.CELL_188_VOLTAGE, new UnsignedWordElement(0x15BB)),
						m(EssFeneconCommercial40.ChannelId.CELL_189_VOLTAGE, new UnsignedWordElement(0x15BC)),
						m(EssFeneconCommercial40.ChannelId.CELL_190_VOLTAGE, new UnsignedWordElement(0x15BD)),
						m(EssFeneconCommercial40.ChannelId.CELL_191_VOLTAGE, new UnsignedWordElement(0x15BE)),
						m(EssFeneconCommercial40.ChannelId.CELL_192_VOLTAGE, new UnsignedWordElement(0x15BF)),
						m(EssFeneconCommercial40.ChannelId.CELL_193_VOLTAGE, new UnsignedWordElement(0x15C0)),
						m(EssFeneconCommercial40.ChannelId.CELL_194_VOLTAGE, new UnsignedWordElement(0x15C1)),
						m(EssFeneconCommercial40.ChannelId.CELL_195_VOLTAGE, new UnsignedWordElement(0x15C2)),
						m(EssFeneconCommercial40.ChannelId.CELL_196_VOLTAGE, new UnsignedWordElement(0x15C3)),
						m(EssFeneconCommercial40.ChannelId.CELL_197_VOLTAGE, new UnsignedWordElement(0x15C4)),
						m(EssFeneconCommercial40.ChannelId.CELL_198_VOLTAGE, new UnsignedWordElement(0x15C5)),
						m(EssFeneconCommercial40.ChannelId.CELL_199_VOLTAGE, new UnsignedWordElement(0x15C6)),
						m(EssFeneconCommercial40.ChannelId.CELL_200_VOLTAGE, new UnsignedWordElement(0x15C7)),
						m(EssFeneconCommercial40.ChannelId.CELL_201_VOLTAGE, new UnsignedWordElement(0x15C8)),
						m(EssFeneconCommercial40.ChannelId.CELL_202_VOLTAGE, new UnsignedWordElement(0x15C9)),
						m(EssFeneconCommercial40.ChannelId.CELL_203_VOLTAGE, new UnsignedWordElement(0x15CA)),
						m(EssFeneconCommercial40.ChannelId.CELL_204_VOLTAGE, new UnsignedWordElement(0x15CB)),
						m(EssFeneconCommercial40.ChannelId.CELL_205_VOLTAGE, new UnsignedWordElement(0x15CC)),
						m(EssFeneconCommercial40.ChannelId.CELL_206_VOLTAGE, new UnsignedWordElement(0x15CD)),
						m(EssFeneconCommercial40.ChannelId.CELL_207_VOLTAGE, new UnsignedWordElement(0x15CE)),
						m(EssFeneconCommercial40.ChannelId.CELL_208_VOLTAGE, new UnsignedWordElement(0x15CF)),
						m(EssFeneconCommercial40.ChannelId.CELL_209_VOLTAGE, new UnsignedWordElement(0x15D0)),
						m(EssFeneconCommercial40.ChannelId.CELL_210_VOLTAGE, new UnsignedWordElement(0x15D1)),
						m(EssFeneconCommercial40.ChannelId.CELL_211_VOLTAGE, new UnsignedWordElement(0x15D2)),
						m(EssFeneconCommercial40.ChannelId.CELL_212_VOLTAGE, new UnsignedWordElement(0x15D3)),
						m(EssFeneconCommercial40.ChannelId.CELL_213_VOLTAGE, new UnsignedWordElement(0x15D4)),
						m(EssFeneconCommercial40.ChannelId.CELL_214_VOLTAGE, new UnsignedWordElement(0x15D5)),
						m(EssFeneconCommercial40.ChannelId.CELL_215_VOLTAGE, new UnsignedWordElement(0x15D6)),
						m(EssFeneconCommercial40.ChannelId.CELL_216_VOLTAGE, new UnsignedWordElement(0x15D7)),
						m(EssFeneconCommercial40.ChannelId.CELL_217_VOLTAGE, new UnsignedWordElement(0x15D8)),
						m(EssFeneconCommercial40.ChannelId.CELL_218_VOLTAGE, new UnsignedWordElement(0x15D9)),
						m(EssFeneconCommercial40.ChannelId.CELL_219_VOLTAGE, new UnsignedWordElement(0x15DA)),
						m(EssFeneconCommercial40.ChannelId.CELL_220_VOLTAGE, new UnsignedWordElement(0x15DB)),
						m(EssFeneconCommercial40.ChannelId.CELL_221_VOLTAGE, new UnsignedWordElement(0x15DC)),
						m(EssFeneconCommercial40.ChannelId.CELL_222_VOLTAGE, new UnsignedWordElement(0x15DD)),
						m(EssFeneconCommercial40.ChannelId.CELL_223_VOLTAGE, new UnsignedWordElement(0x15DE)),
						m(EssFeneconCommercial40.ChannelId.CELL_224_VOLTAGE, new UnsignedWordElement(0x15DF))),
				new FC3ReadRegistersTask(0x1402, Priority.HIGH, //
						m(SymmetricEss.ChannelId.SOC, new UnsignedWordElement(0x1402))));
	}

	@Override
	public String debugLog() {
		return "SoC:" + this.getSoc().value().asString() //
				+ "|L:" + this.getActivePower().value().asString() //
				+ "|Allowed:"
				+ this.channel(ManagedSymmetricEss.ChannelId.ALLOWED_CHARGE_POWER).value().asStringWithoutUnit() + ";"
				+ this.channel(ManagedSymmetricEss.ChannelId.ALLOWED_DISCHARGE_POWER).value().asString() //
				+ "|" + this.getGridMode().value().asOptionString();
	}

	@Override
	public void handleEvent(Event event) {
		if (!this.isEnabled()) {
			return;
		}
		switch (event.getTopic()) {
		case EdgeEventConstants.TOPIC_CYCLE_BEFORE_CONTROLLERS:
			this.defineWorkState();
			break;
		}
	}

	private LocalDateTime lastDefineWorkState = null;

	private void defineWorkState() {
		/*
		 * Set ESS in running mode
		 */
		// TODO this should be smarter: set in energy saving mode if there was no output
		// power for a while and we don't need emergency power.
		LocalDateTime now = LocalDateTime.now();
		if (lastDefineWorkState == null || now.minusMinutes(1).isAfter(this.lastDefineWorkState)) {
			this.lastDefineWorkState = now;
			EnumWriteChannel setWorkStateChannel = this.channel(ChannelId.SET_WORK_STATE);
			try {
				setWorkStateChannel.setNextWriteValue(SetWorkState.START);
			} catch (OpenemsException e) {
				logError(this.log, "Unable to start: " + e.getMessage());
			}
		}
	}

	@Override
	public Power getPower() {
		return this.power;
	}

	@Override
	public int getPowerPrecision() {
		return 100; // the modbus field for SetActivePower has the unit 0.1 kW
	}

	@Override
	public Constraint[] getStaticConstraints() {
		return new Constraint[] {
				// ReactivePower limitations
				this.createPowerConstraint("Commercial40 Min Reactive Power", Phase.ALL, Pwr.REACTIVE,
						Relationship.GREATER_OR_EQUALS, MIN_REACTIVE_POWER),
				this.createPowerConstraint("Commercial40 Max Reactive Power", Phase.ALL, Pwr.REACTIVE,
						Relationship.LESS_OR_EQUALS, MAX_REACTIVE_POWER) };
	}

	@Override
	public ModbusSlaveTable getModbusSlaveTable() {
		return new ModbusSlaveTable( //
				OpenemsComponent.getModbusSlaveNatureTable(), //
				SymmetricEss.getModbusSlaveNatureTable(), //
				ManagedSymmetricEss.getModbusSlaveNatureTable() //
		);
	}

}
