package org.sowatt.sowattweb.integration

import org.slf4j.LoggerFactory
import org.sowatt.sowattweb.domain.Button
import org.sowatt.sowattweb.domain.ControlPoint
import org.sowatt.sowattweb.domain.types.Switch2RockerButtonPosition
import org.sowatt.sowattweb.repository.ButtonRepository
import org.sowatt.sowattweb.repository.ControlPointRepository
import org.sowatt.sowattweb.repository.GoogleSpreadSheetDatabase
import org.sowatt.sowattweb.repository.ToggleCommandRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import uk.co._4ng.enocean.communication.Connection
import uk.co._4ng.enocean.communication.DeviceListener
import uk.co._4ng.enocean.communication.DeviceValueListener
import uk.co._4ng.enocean.devices.DeviceManager
import uk.co._4ng.enocean.devices.EnOceanDevice
import uk.co._4ng.enocean.eep.EEPAttributeChangeJob
import uk.co._4ng.enocean.eep.eep26.attributes.EEP26RockerSwitch2RockerAction
import uk.co._4ng.enocean.eep.eep26.attributes.EEP26RockerSwitch2RockerButtonCount
import uk.co._4ng.enocean.link.LinkLayer
import java.io.IOException
import java.security.GeneralSecurityException
import javax.annotation.PostConstruct
import javax.persistence.EntityManager


@Component
public class EnoceanDeviceListener(private val transactionManager: PlatformTransactionManager, private val googleSpreadSheetDatabase: GoogleSpreadSheetDatabase, private val knxProcessCommunicationWrapper: KNXProcessCommunicationWrapper, private val toggleCommandRepository: ToggleCommandRepository, private val deviceRepository: ControlPointRepository, private val buttonRepository: ButtonRepository, val entityManager: EntityManager) : DeviceListener, DeviceValueListener {
    private val logger = LoggerFactory.getLogger(EnoceanDeviceListener::class.java)

    lateinit private var linkLayer: LinkLayer
    @Value("\${serial.port.id:ttyUSB0}")
    lateinit private var serialPortId: String
    private var deviceManager: DeviceManager = DeviceManager()
    lateinit private var connection: Connection


    @PostConstruct
    @Transactional(propagation = Propagation.REQUIRED)
    @Throws(GeneralSecurityException::class, IOException::class)
    fun initEnoceanDevices() {
        googleSpreadSheetDatabase.init() // we have to introduce this dependency otherwise database might be empty and deviceRepository.findAll() will return an empty list...
        try {
            // create the lowest link layer
            linkLayer = LinkLayer(serialPortId);

            //In case configured port is incorrect
            for (port: String in LinkLayer.getCommsPorts()
            ) {
                logger.info("Found port: {}", port);
            }

            // create a device listener for handling device updates

            deviceManager.addDeviceListener(this);
            deviceManager.addDeviceValueListener(this);

            // register a rocker switch
            for (device: ControlPoint in deviceRepository.findAll())
                deviceManager.registerDevice(device.toEnoceanDevice());

            // create the connection layer
            connection = Connection(linkLayer, deviceManager);

            // connect the link
            linkLayer.connect();
        } catch (e: Exception) {
            System.err.println("The given port does not exist or no device is plugged in" + e);
        }
    }

    override fun modifiedEnOceanDevice(changedDevice: EnOceanDevice?) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun removedEnOceanDevice(changedDevice: EnOceanDevice?) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun addedEnOceanDevice(device: EnOceanDevice?) {
        logger.info("Added device: {}", device?.addressHex)
    }

    override fun deviceAttributeChange(eepAttributeChangeJob: EEPAttributeChangeJob) {
        val transactionTemplate: TransactionTemplate = TransactionTemplate(transactionManager);
        transactionTemplate.execute {
            val enOceanDevice = eepAttributeChangeJob.device
            for (changedAttribute in eepAttributeChangeJob.changedAttributes) {
                for (buttonPosition: Switch2RockerButtonPosition in Switch2RockerButtonPosition.values()) {
                    val button: Button = buttonRepository.getButton(enOceanDevice.addressHex.substring(2), buttonPosition)
                            ?: continue
                    if (changedAttribute
                                    is EEP26RockerSwitch2RockerAction) {
                        val buttonValue = changedAttribute.getButtonValue(buttonPosition.ordinal) //TODO ugly trick: the enum keeps the ordering of the standard...
                        button.isPressed = buttonValue
                    }
                    if (changedAttribute
                                    is EEP26RockerSwitch2RockerButtonCount) {
                        if (button.isPressed && changedAttribute.value == 0) {//TODO how can we put test condition on one line
                            val toggleCommand = toggleCommandRepository.findByButton(button) ?: continue
                            assert(toggleCommand.switchList.size == 1)

                            val switch = toggleCommand.switchList[0]
                            try {
                                val previousValue: Boolean? = knxProcessCommunicationWrapper.readBool(switch.toCommandDP().mainAddress)
                                knxProcessCommunicationWrapper.write(switch.toCommandDP().mainAddress, !previousValue!!)
                            } catch (e: java.lang.Exception) {
                                logger.error("Error while updating switch ${switch.groupAddress}", e)
                            }
                            button.isPressed = false
                        }
                    }
                    entityManager.persist(button)
                    logger.info("button persisted: {}", buttonPosition.toString())
                }

                logger.info("Device: {} Channel: {} Attribute: {} Value: {}", eepAttributeChangeJob.device.addressHex, eepAttributeChangeJob.channelId, changedAttribute.name, changedAttribute.value)
            }
            entityManager.flush()
        }
    }

}