/*
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package br.ufrn.imd.obdandroidapi.commands;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import br.ufrn.imd.obdandroidapi.exceptions.BusInitException;
import br.ufrn.imd.obdandroidapi.exceptions.MisunderstoodCommandException;
import br.ufrn.imd.obdandroidapi.exceptions.NoDataException;
import br.ufrn.imd.obdandroidapi.exceptions.NonNumericResponseException;
import br.ufrn.imd.obdandroidapi.exceptions.ResponseException;
import br.ufrn.imd.obdandroidapi.exceptions.RunException;
import br.ufrn.imd.obdandroidapi.exceptions.StoppedException;
import br.ufrn.imd.obdandroidapi.exceptions.UnableToConnectException;
import br.ufrn.imd.obdandroidapi.exceptions.UnknownErrorException;
import br.ufrn.imd.obdandroidapi.exceptions.UnsupportedCommandException;

import static br.ufrn.imd.obdandroidapi.utils.RegexUtils.BUS_INIT_PATTERN;
import static br.ufrn.imd.obdandroidapi.utils.RegexUtils.COLON_PATTERN;
import static br.ufrn.imd.obdandroidapi.utils.RegexUtils.DIGITS_LETTERS_PATTERN;
import static br.ufrn.imd.obdandroidapi.utils.RegexUtils.SEARCHING_PATTERN;
import static br.ufrn.imd.obdandroidapi.utils.RegexUtils.WHITESPACE_PATTERN;
import static br.ufrn.imd.obdandroidapi.utils.RegexUtils.removeAll;

/**
 * Base OBD command.
 */
public abstract class ObdCommand implements IObdCommand {

    /**
     * Error classes to be tested in order
     */
    private static final List<Class<? extends ResponseException>> ERROR_CLASSES = new ArrayList<>(Arrays.asList(
            UnableToConnectException.class,
            BusInitException.class,
            MisunderstoodCommandException.class,
            NoDataException.class,
            StoppedException.class,
            UnknownErrorException.class,
            UnsupportedCommandException.class
    ));
    protected List<Integer> buffer = null;
    protected String cmd = null;
    protected boolean imperialUnits = false;
    protected String rawData = null;
    protected Long responseDelayInMs = null;
    private long start;
    private long end;

    /**
     * Default constructor to use
     *
     * @param command the command to send
     */
    public ObdCommand(String command) {
        this.cmd = command;
        this.buffer = new ArrayList<>();
    }

    /**
     * Prevent empty instantiation
     */
    private ObdCommand() {
    }

    /**
     * Copy constructor.
     *
     * @param other the ObdCommand to be copied.
     */
    public ObdCommand(ObdCommand other) {
        this(other.cmd);
    }

    /**
     * Sends the OBD-II request and deals with the response.
     * <p>
     * This method CAN be overridden in fake commands.
     *
     * @param in  a {@link java.io.InputStream} object.
     * @param out a {@link java.io.OutputStream} object.
     * @throws java.io.IOException            if any.
     * @throws java.lang.InterruptedException if any.
     */
    public void run(InputStream in, OutputStream out) throws IOException, InterruptedException {
        // Only one command can write and read a data in one time.
        synchronized (ObdCommand.class) {
            start = System.currentTimeMillis();
            sendCommand(out);
            readResult(in);
            end = System.currentTimeMillis();
        }
    }

    /**
     * Sends the OBD-II request.
     * <p>
     * This method may be overridden in subclasses, such as ObMultiCommand or
     * TroubleCodesCommand.
     *
     * @param out The output stream.
     * @throws java.io.IOException            if any.
     * @throws java.lang.InterruptedException if any.
     */
    protected void sendCommand(OutputStream out) throws IOException, InterruptedException {
        // write to OutputStream (i.e.: a BluetoothSocket) with an added Carriage return
        out.write((cmd + "\r").getBytes());
        out.flush();
        if (responseDelayInMs != null && responseDelayInMs > 0) {
            Thread.sleep(responseDelayInMs);
        }
    }

    /**
     * Resend this command.
     *
     * @param out a {@link java.io.OutputStream} object.
     * @throws java.io.IOException            if any.
     * @throws java.lang.InterruptedException if any.
     */
    protected void resendCommand(OutputStream out) throws IOException, InterruptedException {
        out.write("\r".getBytes());
        out.flush();
        if (responseDelayInMs != null && responseDelayInMs > 0) {
            Thread.sleep(responseDelayInMs);
        }
    }

    /**
     * Reads the OBD-II response.
     * <p>
     * This method may be overridden in subclasses, such as ObdMultiCommand.
     *
     * @param in a {@link java.io.InputStream} object.
     * @throws java.io.IOException if any.
     */
    protected void readResult(InputStream in) throws IOException {
        readRawData(in);
        checkForErrors();
        fillBuffer();
        performCalculations();
    }

    /**
     * This method exists so that for each command, there must be a method that is
     * called only once to perform calculations.
     */
    protected abstract void performCalculations();

    /**
     * <p>fillBuffer.</p>
     */
    protected void fillBuffer() {
        /*
         * Imagine the following response 41 0c 00 0d.
         *
         * ELM sends strings!! So, ELM puts spaces between each "byte". And pay
         * attention to the fact that I've put the word byte in quotes, because 41
         * is actually TWO bytes (two chars) in the socket. So, we must do some more
         * processing..
         */
        rawData = removeAll(WHITESPACE_PATTERN, rawData); // removes all [ \t\n\x0B\f\r]

        /*
         * Data may have echo or informative text like "INIT BUS..." or similar.
         * The response ends with two carriage return characters. So we need to take
         * everything from the last carriage return before those two (trimmed above).
         */
        rawData = removeAll(BUS_INIT_PATTERN, rawData);
        rawData = removeAll(COLON_PATTERN, rawData);

        if (!DIGITS_LETTERS_PATTERN.matcher(rawData).matches()) {
            throw new NonNumericResponseException(rawData);
        }

        // read string each two chars
        buffer.clear();
        int initialChar = 0;
        int finalChar = 2;
        while (finalChar <= rawData.length()) {
            buffer.add(Integer.decode("0x" + rawData.substring(initialChar, finalChar)));
            initialChar = finalChar;
            finalChar += 2;
        }
    }

    /**
     * <p>
     * readRawData.</p>
     *
     * @param in a {@link java.io.InputStream} object.
     * @throws java.io.IOException if any.
     */
    protected void readRawData(InputStream in) throws IOException {
        byte b;
        char c;
        StringBuilder res = new StringBuilder();

        // read until '>' arrives OR end of stream reached (-1)
        while ((b = (byte) in.read()) > -1 && (c = (char) b) != '>') {
            res.append(c);
        }

        rawData = removeAll(SEARCHING_PATTERN, res.toString());
        rawData = removeAll(WHITESPACE_PATTERN, rawData);  // removes all [ \t\n\x0B\f\r]
    }

    void checkForErrors() {
        for (Class<? extends ResponseException> errorClass : ERROR_CLASSES) {
            ResponseException messageError;

            try {
                messageError = errorClass.newInstance();
                messageError.setCommand(this.cmd);
            } catch (InstantiationException | IllegalAccessException e) {
                throw new RunException(e);
            }

            if (messageError.isError(rawData)) {
                throw messageError;
            }
        }
    }

    /**
     * <p>getResult.</p>
     *
     * @return the raw command response in string representation.
     */
    public String getResult() {
        return rawData;
    }

    /**
     * <p>getFormattedResult.</p>
     *
     * @return a formatted command response in string representation.
     */
    public abstract String getFormattedResult();

    /**
     * <p>getCalculatedResult.</p>
     *
     * @return the command response in string representation, without formatting.
     */
    public abstract String getCalculatedResult();

    /**
     * <p>Getter for the field <code>buffer</code>.</p>
     *
     * @return a list of integers
     */
    protected List<Integer> getBuffer() {
        return buffer;
    }

    /**
     * <p>isImperialUnits.</p>
     *
     * @return true if imperial units are used, or false otherwise
     */
    public boolean isImperialUnits() {
        return imperialUnits;
    }

    /**
     * The unit of the result, as used in {@link #getFormattedResult()}
     *
     * @return a String representing a unit or "", never null
     */
    public String getResultUnit() {
        return "";  // no unit by default
    }

    /**
     * Set to 'true' if you want to use imperial units, false otherwise. By
     * default this value is set to 'false'.
     *
     * @param isImperial a boolean.
     */
    public void setImperialUnits(boolean isImperial) {
        this.imperialUnits = isImperial;
    }

    /**
     * <p>getName.</p>
     *
     * @return the OBD command name.
     */
    public abstract String getName();

    /**
     * Time the command waits before returning from #sendCommand()
     *
     * @return delay in ms (may be null)
     */
    public Long getResponseTimeDelay() {
        return responseDelayInMs;
    }

    /**
     * Time the command waits before returning from #sendCommand()
     *
     * @param responseDelayInMs a Long (can be null)
     */
    public void setResponseTimeDelay(Long responseDelayInMs) {
        this.responseDelayInMs = responseDelayInMs;
    }


    /**
     * <p>Getter for the field <code>start</code>.</p>
     *
     * @return a long.
     */
    public long getStart() {
        return start;
    }

    /**
     * <p>Setter for the field <code>start</code>.</p>
     *
     * @param start a long.
     */
    public void setStart(long start) {
        this.start = start;
    }

    /**
     * <p>Getter for the field <code>end</code>.</p>
     *
     * @return a long.
     */
    public long getEnd() {
        return end;
    }

    /**
     * <p>Setter for the field <code>end</code>.</p>
     *
     * @param end a long.
     */
    public void setEnd(long end) {
        this.end = end;
    }

    /**
     * <p>getCommandPID.</p>
     *
     * @return a {@link java.lang.String} object.
     * @since 1.0-RC12
     */
    public final String getCommandPID() {
        return cmd.substring(3);
    }

    /**
     * <p>getCommandMode.</p>
     *
     * @return a {@link java.lang.String} object.
     */
    public final String getCommandMode() {
        return cmd.length() >= 2 ? cmd.substring(0, 2) : cmd;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        ObdCommand that = (ObdCommand) o;

        return cmd != null ? cmd.equals(that.cmd) : that.cmd == null;
    }

    @Override
    public int hashCode() {
        return cmd != null ? cmd.hashCode() : 0;
    }
}
