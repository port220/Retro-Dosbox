/*
 *  Port220 serial backend for DOSBox-X.
 *
 *  Why this exists
 *  ---------------
 *  DOSBox-X's own `nullmodem` backend is compiled only when C_MODEM is
 *  defined, and C_MODEM requires SDL_net. The Android core is configured with
 *  --disable-sdlnet (upstream builds a DOS *game* emulator, where serial over
 *  TCP is dead weight), so nullmodem.cpp is not compiled at all and
 *  serialport.cpp does not even recognise the string "nullmodem". A
 *  [serial] line naming it is silently ignored and the guest gets nothing.
 *
 *  Re-enabling SDL_net for SDL3 is awkward: the tree's SDL2_net probe is
 *  gated on SDL_STRING=SDL2, SDL3_net has an incompatible API, and the
 *  bundled SDL_net.h is SDL1-era. Rather than reconcile that, this backend
 *  opens a plain POSIX socket. Android has full BSD sockets, so there is no
 *  dependency to satisfy and nothing to go stale across SDL versions.
 *
 *  What it does
 *  ------------
 *  Connects to the Port220 bridge on 127.0.0.1 and moves bytes between that
 *  socket and the emulated UART. Nothing else: no RTS/DTR handshake, no
 *  telnet negotiation, no packet gathering. The far end is a raw byte pipe to
 *  an FT232R, not another DOSBox, so any framing we invented here would land
 *  on the wire as protocol corruption.
 *
 *  Deliberately NOT modelled on nullmodem.cpp's receive path. That has a
 *  three-state machine with retry counters to emulate a real modem's flow
 *  control under an unreliable network link. Over loopback to a local bridge
 *  none of that applies, and its rx_retry blocking would add latency to a
 *  link that has a ~150ms budget end to end.
 *
 *  Config syntax (parsed in the constructor):
 *      serial1=port220 port:6403 host:127.0.0.1
 *  Both are optional; the defaults match the bridge service.
 */

#ifndef INCLUDEGUARD_PORT220SERIAL_H
#define INCLUDEGUARD_PORT220SERIAL_H

#include "serialport.h"
#include <string>

/* Our own polling event id. The base class reserves 0..SERIAL_BASE_EVENT_COUNT
 * (7), and nullmodem's ids are not compiled in here, but numbering above the
 * base count keeps this safe either way. */
#define SERIAL_PORT220_POLL_EVENT (SERIAL_BASE_EVENT_COUNT + 1)
#define SERIAL_PORT220_RX_PACE_EVENT (SERIAL_BASE_EVENT_COUNT + 2)

class CSerialPort220 : public CSerial {
public:
    CSerialPort220(Bitu id, CommandLine *cmd);
    virtual ~CSerialPort220();

    void setRTSDTR(bool rts, bool dtr) override;
    void setRTS(bool val) override;
    void setDTR(bool val) override;

    void updatePortConfig(uint16_t divider, uint8_t lcr) override;
    void updateMSR() override;
    void transmitByte(uint8_t val, bool first) override;
    void setBreak(bool value) override;
    void handleUpperEvent(uint16_t type) override;

private:
    int  sock;              /* -1 when not connected */
    bool connected;

    /* Retry state. The bridge and the emulator are started independently, so
     * either order must work; the port stays present and reconnects when the
     * bridge appears. */
    std::string retry_host;
    int         retry_port;
    int         retry_ticks;

    /* Receive pacing. True while a byte has been delivered and the next may
     * not be until one byte time has elapsed -- see pollIncoming(). */
    bool rx_pacing;

    /* Diagnostics: how many control-line changes have been logged, so the
     * log shows what EMSAN1 does on open without being flooded thereafter. */
    int  ctrl_logs;

    bool openSocket(const char *host, int port);
    void closeSocket();
    void pollIncoming();
};

#endif /* INCLUDEGUARD_PORT220SERIAL_H */
