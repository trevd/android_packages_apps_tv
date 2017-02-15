/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include <stdio.h>
#include <stdlib.h>
#include <errno.h>
#include <unistd.h>
#include <limits.h>
#include <string.h>
#include <ctype.h>
#include <fcntl.h>
#include <sys/poll.h>
#include <sys/ioctl.h>
#include <linux/dvb/dmx.h>
#include <linux/dvb/frontend.h>

#define LOG_TAG "DvbManager"
#include "logging.h"

#include "DvbManager.h"

#define SLOF (11700*1000UL)
#define LOF1 (9750*1000UL)
#define LOF2 (10600*1000UL)


static double currentTimeMillis() {
    struct timeval tv;
    gettimeofday(&tv, (struct timezone *) NULL);
    return tv.tv_sec * 1000.0 + tv.tv_usec / 1000.0;
}

DvbManager::DvbManager(JNIEnv *env, jobject)
        : mFeFd(-1),
          mDemuxFd(-1),
          mDvrFd(-1),
          mPatFilterFd(-1),
          mFeHasLock(false),
          mHasPendingTune(false) {
    jclass clazz = env->FindClass(
        "com/android/tv/tuner/TunerHal");
    mOpenDvbFrontEndMethodID = env->GetMethodID(
        clazz, "openDvbFrontEndFd", "()I");
    mOpenDvbDemuxMethodID = env->GetMethodID(
        clazz, "openDvbDemuxFd", "()I");
    mOpenDvbDvrMethodID = env->GetMethodID(
        clazz, "openDvbDvrFd", "()I");
}

DvbManager::~DvbManager() {
    reset();
}

bool DvbManager::isFeLocked() {
    struct pollfd pollFd;
    pollFd.fd = mFeFd;
    pollFd.events = POLLIN;
    pollFd.revents = 0;
    int poll_result = poll(&pollFd, NUM_POLLFDS, FE_POLL_TIMEOUT_MS);
    if (poll_result > 0 && (pollFd.revents & POLLIN)) {
        struct dvb_frontend_event kevent;
        memset(&kevent, 0, sizeof(kevent));
        if (ioctl(mFeFd, FE_GET_EVENT, &kevent) == 0) {
            return (kevent.status & FE_HAS_LOCK);
        }
    }
    return false;
}

int DvbManager::tune(JNIEnv *env, jobject thiz,
        const int frequency, const char *modulationStr, int timeout_ms) {
    resetExceptFe();

    struct dvb_frontend_parameters feParams;
    memset(&feParams, 0, sizeof(struct dvb_frontend_parameters));
    feParams.frequency = frequency;
    if (strcmp(modulationStr, "8VSB") == 0) {
        feParams.u.vsb.modulation = VSB_8;
    } else if (strcmp(modulationStr, "QAM256") == 0) {
        feParams.u.vsb.modulation = QAM_256;
    } else {
        ALOGE("Unrecognized modulation mode : %s", modulationStr);
        return -1;
    }

    if (mHasPendingTune) {
        return -1;
    }
    if (openDvbFe(env, thiz) != 0) {
        return -1;
    }

    feParams.inversion = INVERSION_AUTO;
    /* Check frontend capability */
    struct dvb_frontend_info feInfo;
    if (ioctl(mFeFd, FE_GET_INFO, &feInfo) != -1) {
        if (!(feInfo.caps & FE_CAN_INVERSION_AUTO)) {
            // FE can't do INVERSION_AUTO, trying INVERSION_OFF instead
            feParams.inversion = INVERSION_OFF;
        }
    }

    if (ioctl(mFeFd, FE_SET_FRONTEND, &feParams) != 0) {
        ALOGD("Can't set Frontend : %s", strerror(errno));
        return -1;
    }

    int lockSuccessCount = 0;
    double tuneClock = currentTimeMillis();
    while (currentTimeMillis() - tuneClock < timeout_ms) {
        if (mHasPendingTune) {
            // Return 0 here since we already call FE_SET_FRONTEND, and return due to having pending
            // tune request. And the frontend setting could be successful.
            mFeHasLock = true;
            return 0;
        }
        bool lockStatus = isFeLocked();
        if (lockStatus) {
            lockSuccessCount++;
        } else {
            lockSuccessCount = 0;
        }
        ALOGI("Lock status : %s", lockStatus ? "true" : "false");
        if (lockSuccessCount >= FE_CONSECUTIVE_LOCK_SUCCESS_COUNT) {
            mFeHasLock = true;
            openDvbDvr(env, thiz);
            return 0;
        }
    }

    return -1;
}

int DvbManager::stopTune() {
    reset();
    usleep(DVB_TUNE_STOP_DELAY_MS);
    return 0;
}

int DvbManager::openDvbFeFromSystemApi(JNIEnv *env, jobject thiz) {
    int fd = (int) env->CallIntMethod(thiz, mOpenDvbFrontEndMethodID);
    fcntl(fd, F_SETFL, fcntl(fd, F_GETFL) | O_NONBLOCK);
    return fd;
}

int DvbManager::openDvbDemuxFromSystemApi(JNIEnv *env, jobject thiz) {
    int fd = (int) env->CallIntMethod(thiz, mOpenDvbDemuxMethodID);
    fcntl(fd, F_SETFL, fcntl(fd, F_GETFL) | O_NONBLOCK);
    return fd;
}

int DvbManager::openDvbDvrFromSystemApi(JNIEnv *env, jobject thiz) {
    int fd = (int) env->CallIntMethod(thiz, mOpenDvbDvrMethodID);
    fcntl(fd, F_SETFL, fcntl(fd, F_GETFL) | O_NONBLOCK);
    return fd;
}

int DvbManager::openDvbFe(JNIEnv *env, jobject thiz) {
    if (mFeFd == -1) {
        if ((mFeFd = openDvbFeFromSystemApi(env, thiz)) < 0) {
            ALOGD("Can't open FE file : %s", strerror(errno));
            return -1;
        }
    }

    struct dvb_frontend_info info;
    if (ioctl(mFeFd, FE_GET_INFO, &info) == 0) {
        const char *types;
        switch (info.type) {
            case FE_QPSK:
                types = "DVB-S";
                break;
            case FE_QAM:
                types = "DVB-C";
                break;
            case FE_OFDM:
                types = "DVB-T";
                break;
            case FE_ATSC:
                types = "ATSC";
                break;
            default:
                types = "Unknown";
        }
        ALOGI("Using frontend \"%s\", type %s", info.name, types);
    }
    return 0;
}

int DvbManager::startTsPidFilter(JNIEnv *env, jobject thiz, int pid, int filterType) {
    Mutex::Autolock autoLock(mFilterLock);

    if (mPidFilters.find(pid) != mPidFilters.end() || (mPatFilterFd != -1 && pid == PAT_PID)) {
        return 0;
    }

    if (mHasPendingTune) {
        return -1;
    }

    int demuxFd;
    if ((demuxFd = openDvbDemuxFromSystemApi(env, thiz)) < 0) {
        ALOGD("Can't open DEMUX file : %s", strerror(errno));
        return -1;
    }

    struct dmx_pes_filter_params filter;
    memset(&filter, 0, sizeof(filter));
    filter.pid = pid;
    filter.input = DMX_IN_FRONTEND;
    switch (filterType) {
        case FILTER_TYPE_AUDIO:
            filter.pes_type = DMX_PES_AUDIO;
            break;
        case FILTER_TYPE_VIDEO:
            filter.pes_type = DMX_PES_VIDEO;
            break;
        case FILTER_TYPE_PCR:
            filter.pes_type = DMX_PES_PCR;
            break;
        default:
            filter.pes_type = DMX_PES_OTHER;
            break;
    }
    filter.output = DMX_OUT_TS_TAP;
    filter.flags |= (DMX_CHECK_CRC | DMX_IMMEDIATE_START);

    // create a pes filter
    if (ioctl(demuxFd, DMX_SET_PES_FILTER, &filter)) {
        close(demuxFd);
        return -1;
    }

    if (pid != PAT_PID) {
        mPidFilters.insert(std::pair<int, int>(pid, demuxFd));
    } else {
        mPatFilterFd = demuxFd;
    }

    return 0;
}

void DvbManager::closeAllDvbPidFilter() {
    // Close all dvb pid filters except PAT filter to maintain the opening status of the device.
    Mutex::Autolock autoLock(mFilterLock);

    for (std::map<int, int>::iterator it(mPidFilters.begin());
                it != mPidFilters.end(); it++) {
        close(it->second);
    }
    mPidFilters.clear();
    // Close mDvrFd to make sure there is not buffer from previous channel left.
    closeDvbDvr();
}

void DvbManager::closePatFilter() {
    Mutex::Autolock autoLock(mFilterLock);

    if (mPatFilterFd != -1) {
        close(mPatFilterFd);
        mPatFilterFd = -1;
    }
}

int DvbManager::openDvbDvr(JNIEnv *env, jobject thiz) {
    if ((mDvrFd = openDvbDvrFromSystemApi(env, thiz)) < 0) {
        ALOGD("Can't open DVR file : %s", strerror(errno));
        return -1;
    }
    return 0;
}
int DvbManager::openDvbDemux(JNIEnv *env, jobject thiz) {
    if ((mDemuxFd = openDvbDemuxFromSystemApi(env, thiz)) < 0) {
        ALOGD("Can't open DMX file : %s", strerror(errno));
        return -1;
    }
    return 0;
}

void DvbManager::closeDvbFe() {
    if (mFeFd != -1) {
        close(mFeFd);
        mFeFd = -1;
    }
}

void DvbManager::closeDvbDvr() {
    if (mDvrFd != -1) {
        close(mDvrFd);
        mDvrFd = -1;
    }
}
void DvbManager::closeDvbDemux() {
    if (mDemuxFd != -1) {
        close(mDemuxFd);
        mDemuxFd = -1;
    }
}


void DvbManager::reset() {
    mFeHasLock = false;
    closeDvbDvr();
    closeDvbDemux();
    closeAllDvbPidFilter();
    closePatFilter();
    closeDvbFe();
}

void DvbManager::resetExceptFe() {
    mFeHasLock = false;
    closeDvbDvr();
    closeDvbDemux();
    closeAllDvbPidFilter();
    closePatFilter();
}

int DvbManager::readTsStream(JNIEnv *env, jobject thiz,
        uint8_t *tsBuffer, int tsBufferSize, int timeout_ms) {
    if (!mFeHasLock) {
        usleep(DVB_ERROR_RETRY_INTERVAL_MS);
        return -1;
    }

    if (mDvrFd == -1) {
        openDvbDvr(env, thiz);
    }
   if (mDemuxFd == -1) {
        openDvbDemux(env, thiz);
    }
    struct pollfd pollFd;
    pollFd.fd = mDvrFd;
    pollFd.events = POLLIN|POLLPRI|POLLERR;
    pollFd.revents = 0;
    int poll_result = poll(&pollFd, NUM_POLLFDS, timeout_ms);
    if (poll_result == 0) {
        return 0;
    } else if (poll_result == -1 || pollFd.revents & POLLERR) {
        ALOGD("Can't read DVR : %s", strerror(errno));
        // TODO: Find how to recover this situation correctly.
        closeDvbDvr();
        usleep(DVB_ERROR_RETRY_INTERVAL_MS);
        return -1;
    }
    return read(mDvrFd, tsBuffer, tsBufferSize);
}

void DvbManager::setHasPendingTune(bool hasPendingTune) {
    mHasPendingTune = hasPendingTune;
}


int DvbManager::clearDvbCmdSeq() {

	Mutex::Autolock autoLock(mFilterLock);
	struct dtv_property p[] = {
		{ .cmd = DTV_CLEAR },
	};

	struct dtv_properties cmdseq = {
		.num = 1,
		.props = p
	};

	if ((ioctl(mFeFd, FE_SET_PROPERTY, &cmdseq)) == -1) {
		ALOGE("FE_SET_PROPERTY DTV_CLEAR failed");
		return -1;
	}
	return 0;
}

int DvbManager::tuneV5(unsigned int ifreq, unsigned int sr, unsigned int delsys,
		    unsigned int modulation,  unsigned int  v,  unsigned int  fec, unsigned int rolloff)
{
	struct dvb_frontend_event ev;
	struct dtv_property p[] = {
		{ .cmd = DTV_DELIVERY_SYSTEM,	.u.data = delsys },
		{ .cmd = DTV_FREQUENCY,		.u.data = ifreq },
		{ .cmd = DTV_MODULATION,	.u.data = modulation },
		{ .cmd = DTV_SYMBOL_RATE,	.u.data = sr },
		{ .cmd = DTV_INNER_FEC,		.u.data = fec },
		{ .cmd = DTV_VOLTAGE,		.u.data = v },
		{ .cmd = DTV_INVERSION,		.u.data = INVERSION_AUTO },
		{ .cmd = DTV_ROLLOFF,		.u.data = rolloff },
		{ .cmd = DTV_PILOT,		.u.data = PILOT_AUTO },
		{ .cmd = DTV_TUNE },
	};
	struct dtv_properties cmdseq = {
		.num = 10,
		.props = p
	};

	/* discard stale QPSK events */
	while (1) {
		if (ioctl(mFeFd, FE_GET_EVENT, &ev) == -1)
		break;
	}

	if ((delsys != SYS_DVBS) && (delsys != SYS_DVBS2))
		return -EINVAL;

	if ((ioctl(mFeFd, FE_SET_PROPERTY, &cmdseq)) == -1) {
		perror("FE_SET_PROPERTY failed");
		return -1;
	}

	return 0;
}

int DvbManager::tuneDVB(JNIEnv *env, jobject thiz, const int deliverysystem, const int frequency, const char *polarizationStr, int symbolrate, const char *fecStr, const double rolloff, const char *modulationStr, int timeout_ms) 
{
    resetExceptFe();
	ALOGD("tuneDVB deliverysystem: %d", deliverysystem);
    if (mHasPendingTune) {
        return -1;
    }
    if (openDvbFe(env, thiz) != 0) {
        return -1;
    }

	clearDvbCmdSeq();
	
	uint32_t real_frequency = 0;
	int hiband = 0;
	if(frequency >= SLOF)
		hiband = 1;
	
	if (hiband)
		real_frequency = frequency - LOF2;
	else {
		if (frequency < LOF1)
			real_frequency = LOF1 - frequency;
		else
			real_frequency = frequency - LOF1;
	}
	ALOGD("tuneDVB real_frequency: %u", real_frequency);
	
    fe_sec_voltage_t voltage = voltage = SEC_VOLTAGE_OFF;
    char polarization;
    if (toupper(polarizationStr[0]) == 'V') {
		voltage = SEC_VOLTAGE_13;
		ALOGD("tuneDVB voltage: SEC_VOLTAGE_13");
	} else {
		voltage = SEC_VOLTAGE_18;
		ALOGD("tuneDVB voltage: SEC_VOLTAGE_18");
	}
	
	fe_delivery_system delsys = SYS_UNDEFINED ;
	if ( deliverysystem == 2 ) {
		delsys = SYS_DVBS;
		ALOGD("tuneDVB deliverysystem: SYS_DVBS");
	} else {
		delsys = SYS_DVBS2;
		ALOGD("tuneDVB deliverysystem: SYS_DVBS22");
	}
	fe_modulation modulation;
    if (strcmp(modulationStr, "QPSK") == 0) {
        modulation = QPSK;
        ALOGD("tuneDVB modulation = QPSK");
    } else if (strcmp(modulationStr, "8PSK") == 0) {
        modulation = PSK_8;
         ALOGD("tuneDVB modulation = 8PSK");
	}
	
	fe_code_rate fec = FEC_NONE;
    if (strcmp(fecStr, "5/6") == 0) {
        fec = FEC_5_6;
        ALOGD("tuneDVB fec = FEC_5_6;");
    }
	
	tuneV5(real_frequency, symbolrate, delsys, modulation, voltage, fec, ROLLOFF_35);



    int lockSuccessCount = 0;
    double tuneClock = currentTimeMillis();
    while (currentTimeMillis() - tuneClock < timeout_ms) {
        if (mHasPendingTune) {
            // Return 0 here since we already call FE_SET_FRONTEND, and return due to having pending
            // tune request. And the frontend setting could be successful.
            mFeHasLock = true;
            return 0;
        }
        bool lockStatus = isFeLocked();
        if (lockStatus) {
            lockSuccessCount++;
        } else {
            lockSuccessCount = 0;
        }
        ALOGI("Lock status : %s", lockStatus ? "true" : "false");
        if (lockSuccessCount >= FE_CONSECUTIVE_LOCK_SUCCESS_COUNT) {
            mFeHasLock = true;
            openDvbDvr(env, thiz);
            return 0;
        }
    }

    return -1;
}

int DvbManager::startSectionFilter(JNIEnv *env, jobject thiz, int pid, int tid) {
    Mutex::Autolock autoLock(mFilterLock);

//    if (mPidFilters.find(pid) != mPidFilters.end() || (mPatFilterFd != -1 && pid == PAT_PID)) {
//        return 0;
//    }

    if (mHasPendingTune) {
        return -1;
    }


    if ((openDvbDemux(env, thiz)) < 0) {
        ALOGD("Can't open DEMUX file : %s", strerror(errno));
        return -1;
    }

	struct dmx_sct_filter_params sctfilter;
	memset(&sctfilter, 0, sizeof(struct dmx_sct_filter_params));

	sctfilter.pid = pid;
	sctfilter.flags = DMX_IMMEDIATE_START;
	sctfilter.timeout = 0;

	if (tid < 0x100 && tid > 0) {
		sctfilter.filter.filter[0] = (uint8_t) tid;
		sctfilter.filter.mask[0]   = 0xff;
	}

	sctfilter.timeout = 0;
	sctfilter.flags = DMX_IMMEDIATE_START | DMX_CHECK_CRC;

	if (ioctl(mDemuxFd, DMX_SET_FILTER, &sctfilter) == -1) {
		ALOGE("ioctl DMX_SET_FILTER failed");
		close(mDemuxFd);
	}

    if (pid != PAT_PID) {
        mPidFilters.insert(std::pair<int, int>(pid, mDemuxFd));
    } else {
        mPatFilterFd = mDemuxFd;
    }

    return 0;
}

